package org.dromara.dms.doc.service.impl;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.service.FolderService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.dms.doc.service.ZipDownloadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ZIP 打包下载服务实现（流式）
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZipDownloadServiceImpl implements ZipDownloadService {

    /** 单次读取/写入的数据块大小 */
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /** 记录失败清单的说明文件（仅在真的有问题时才写入 ZIP） */
    private static final String MANIFEST_NAME = "_下载说明.txt";

    /** 遍历深度的保护上限，避免脏数据造成的环形路径把栈打爆 */
    private static final int MAX_DEPTH = 64;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;
    private final DocFolderMapper folderMapper;
    private final DocFileMapper fileMapper;
    private final FolderService folderService;
    private final PermissionService permissionService;

    /** 单次打包的最大文件数（0 或负数表示不限制） */
    @Value("${dms.download.zip.max-files:2000}")
    private int maxFiles;

    /**
     * 单次打包的最大未压缩字节数，默认 4GB
     *
     * <p>刻意压在 4GB 以内：超过 4GB 会触发 ZIP64 扩展，虽然 Java 与 Windows 10+ 都支持，
     * 但不少旧解压工具/嵌入式设备不支持。真的需要更大时再放开并接受 ZIP64。
     */
    @Value("${dms.download.zip.max-bytes:4294967296}")
    private long maxBytes;

    // ==================================================================
    // 计划
    // ==================================================================

    @Override
    public DocFolder requireFolder(Long folderId) {
        DocFolder folder = folderMapper.selectById(folderId);
        if (folder == null || folder.getDeletedAt() != null) {
            throw new ServiceException("文件夹不存在或已在回收站");
        }
        return folder;
    }

    @Override
    public ZipPlan planFolder(Long folderId, Long userId) {
        return plan(List.of(folderId), List.of(), userId);
    }

    @Override
    public ZipPlan plan(List<Long> folderIds, List<Long> fileIds, Long userId) {
        List<Long> folders = folderIds == null ? List.of() : folderIds;
        List<Long> files = fileIds == null ? List.of() : fileIds;
        if (folders.isEmpty() && files.isEmpty()) {
            throw new ServiceException("请选择要下载的文件夹或文件");
        }

        List<ZipEntryPlan> entries = new ArrayList<>();
        int[] counters = new int[]{0, 0};   // [文件数, 被跳过的无权限文件数]
        long[] total = new long[]{0L};

        String rootName = null;
        Long entryResourceId = folders.size() == 1 ? folders.get(0) : null;
        Set<String> usedNames = new HashSet<>();

        // 1) 文件夹：逐个递归打包
        for (Long folderId : folders) {
            DocFolder folder = requireFolder(folderId);
            if (rootName == null) {
                rootName = folder.getFolderName();
            }
            // 多个文件夹时，各自作为顶层目录；单个文件夹时也保留顶层目录（解压出文件夹而不是散文件）
            String prefix = sanitizeSegment(folder.getFolderName());
            collectFolder(folder, prefix, userId, entries, counters, total);
        }

        // 2) 单独选中的文件：放在 ZIP 根下
        if (!files.isEmpty()) {
            List<DocFile> selected = new ArrayList<>();
            for (Long fileId : files) {
                DocFile f = fileMapper.selectById(fileId);
                if (f != null && f.getDeletedAt() == null) {
                    selected.add(f);
                }
            }
            // 批量算权限：一次拿全，避免逐个查目录链
            Map<Long, Integer> flags = permissionService.computeFileFlagsBatch(selected, userId);
            for (DocFile f : selected) {
                int fg = flags.getOrDefault(f.getFileId(), 0);
                if (!PermissionFlag.has(fg, PermissionFlag.DOWNLOAD)) {
                    counters[1]++;
                    continue;
                }
                String name = uniqueName(usedNames, sanitizeSegment(f.getFileName()));
                entries.add(new ZipEntryPlan(name, false,
                        f.getStorageBucket() == null ? minIoConfig.getBucket() : f.getStorageBucket(),
                        f.getStorageKey(), f.getFileId(),
                        f.getFileSize() == null ? 0L : f.getFileSize()));
                counters[0]++;
                total[0] += f.getFileSize() == null ? 0L : f.getFileSize();
            }
            if (rootName == null && !selected.isEmpty()) {
                rootName = "所选文件";
            }
        }

        // 3) 上限校验（在写响应之前完成，这样才能返回可读的 JSON 错误）
        String reason = null;
        if (maxFiles > 0 && counters[0] > maxFiles) {
            reason = "本次将打包 " + counters[0] + " 个文件，超过单次上限 " + maxFiles
                    + " 个，请分批下载";
        } else if (maxBytes > 0 && total[0] > maxBytes) {
            reason = "本次将打包 " + humanSize(total[0]) + "，超过单次上限 "
                    + humanSize(maxBytes) + "，请分批下载";
        }

        return new ZipPlan(rootName == null ? "download" : rootName, entryResourceId, entries,
                counters[0], total[0], counters[1], reason != null, reason);
    }

    /**
     * 递归收集一个文件夹下的内容
     *
     * <p>只遍历用户<b>可见</b>的子目录（{@link FolderService#listChildren} 已按可见性过滤），
     * 文件则逐个按「下载」位过滤 —— 逐文件判定是必须的：文件级授权可能覆盖
     * 从文件夹继承来的授权，只看文件夹会漏判。
     */
    private void collectFolder(DocFolder folder, String prefix, Long userId,
                               List<ZipEntryPlan> entries, int[] counters, long[] total) {
        collectFolder(folder, prefix, userId, entries, counters, total, 0);
    }

    private void collectFolder(DocFolder folder, String prefix, Long userId,
                               List<ZipEntryPlan> entries, int[] counters, long[] total, int depth) {
        if (depth > MAX_DEPTH) {
            log.warn("打包下载：目录层级超过 {} 层，已停止展开: folderId={}", MAX_DEPTH, folder.getFolderId());
            return;
        }

        // 目录条目：即使为空也要写，否则解压后空文件夹会凭空消失、结构错乱
        entries.add(new ZipEntryPlan(prefix + "/", true, null, null, null, 0L));

        // 1) 本层文件：批量算权限（同目录只算一次目录链）
        List<DocFile> files = fileMapper.listByFolder(folder.getFolderId());
        if (!files.isEmpty()) {
            Map<Long, Integer> flags = permissionService.computeFileFlagsBatch(files, userId);
            Set<String> usedNames = new HashSet<>();
            for (DocFile f : files) {
                int fg = flags.getOrDefault(f.getFileId(), 0);
                if (!PermissionFlag.has(fg, PermissionFlag.DOWNLOAD)) {
                    // 无下载权限：跳过。数量会在清单里告知用户，避免以为文件丢了
                    counters[1]++;
                    continue;
                }
                if (f.getStorageKey() == null || f.getStorageKey().isBlank()) {
                    log.warn("打包下载：文件没有存储对象，跳过: fileId={}, name={}",
                            f.getFileId(), f.getFileName());
                    counters[1]++;
                    continue;
                }
                String name = uniqueName(usedNames, sanitizeSegment(f.getFileName()));
                entries.add(new ZipEntryPlan(prefix + "/" + name, false,
                        f.getStorageBucket() == null ? minIoConfig.getBucket() : f.getStorageBucket(),
                        f.getStorageKey(), f.getFileId(),
                        f.getFileSize() == null ? 0L : f.getFileSize()));
                counters[0]++;
                total[0] += f.getFileSize() == null ? 0L : f.getFileSize();
            }
        }

        // 2) 子目录：只取用户可见的
        List<DocFolder> children = folderService.listChildren(folder.getFolderId(), userId);
        for (DocFolder child : children) {
            collectFolder(child, prefix + "/" + sanitizeSegment(child.getFolderName()),
                    userId, entries, counters, total, depth + 1);
        }
    }

    // ==================================================================
    // 流式写出
    // ==================================================================

    @Override
    public ZipResult streamZip(ZipPlan plan, Long userId, HttpServletResponse response) throws IOException {
        String zipName = sanitizeSegment(plan.rootName()) + ".zip";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        // filename* 用 RFC 5987 编码中文名；同时给出 ASCII 兜底名，兼容老浏览器
        String encoded = URLEncoder.encode(zipName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"download.zip\"; filename*=UTF-8''" + encoded);
        // 打包结果随时可能变，禁止任何中间层缓存
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        // 刻意不设 Content-Length：ZIP 总大小取决于压缩器内部的块框架，无法可靠预估，
        // 写错会让浏览器判定下载损坏。改用 chunked 传输，由容器自动处理。

        return writeZip(plan, userId, response.getOutputStream(), null);
    }

    @Override
    public ZipResult writeZip(ZipPlan plan, Long userId, OutputStream out,
                              ProgressListener listener) throws IOException {
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int writtenFiles = 0;
        long writtenBytes = 0L;

        // 刻意不套 BufferedOutputStream：ZipOutputStream 自己就有缓冲，
        // 而我们用 64KB 的数据块写入，已经足够摊薄系统调用
        ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8);
        // 关键：level 0 = 不压缩。见 ZipDownloadService 类注释里对 STORED 的说明
        zos.setLevel(Deflater.NO_COMPRESSION);
        // 注意：java.util.zip.ZipOutputStream 没有 ZIP64 开关（Zip64Mode 是
        // commons-compress 的类）。JDK 会在条目超过 4GB 等情况时自动写 ZIP64 扩展字段，
        // 无需也无法在这里设置；真要禁用只能靠系统属性 jdk.util.zip.inhibitZip64。

        byte[] buf = new byte[COPY_BUFFER_SIZE];
        try {
            for (ZipEntryPlan entry : plan.entries()) {
                if (entry.directory()) {
                    writeDirectoryEntry(zos, entry.entryName());
                    if (listener != null) {
                        listener.onEntry(entry.entryName(), writtenFiles, writtenBytes, plan.totalBytes());
                    }
                    continue;
                }

                InputStream in = null;
                boolean headerWritten = false;
                try {
                    in = minioClient.getObject(GetObjectArgs.builder()
                            .bucket(entry.bucket())
                            .object(entry.storageKey())
                            .build());

                    // 预读第一块：对象不存在 / 无权限 / MinIO 不可达这类错误
                    // 大多在建立流或首次读取时暴露。先读再写条目头，
                    // 就能"干净地跳过"——不会在 ZIP 里留下半个损坏条目。
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) {
                        n = 0;
                    }

                    zos.putNextEntry(new ZipEntry(entry.entryName()));
                    headerWritten = true;
                    if (n > 0) {
                        zos.write(buf, 0, n);
                    }
                    long copied = n;
                    while ((n = in.read(buf, 0, buf.length)) > 0) {
                        zos.write(buf, 0, n);
                        copied += n;
                    }
                    zos.closeEntry();

                    writtenFiles++;
                    writtenBytes += copied;

                    // 期望大小与实际不符 → 内容被截断（MinIO 侧对象被替换/损坏等）
                    if (entry.size() > 0 && copied != entry.size()) {
                        failed.add(entry.entryName() + "（期望 " + humanSize(entry.size())
                                + "，实际 " + humanSize(copied) + "，内容不完整）");
                        log.error("打包下载：文件内容不完整 fileId={}, name={}, 期望={}, 实际={}",
                                entry.fileId(), entry.entryName(), entry.size(), copied);
                    }
                } catch (Exception ex) {
                    if (!headerWritten) {
                        // 尚未写入任何数据：可以干净跳过，ZIP 里不留痕迹
                        skipped.add(entry.entryName() + "（读取失败：" + rootMessage(ex) + "）");
                        log.error("打包下载：读取文件失败，已跳过 fileId={}, name={}, key={}",
                                entry.fileId(), entry.entryName(), entry.storageKey(), ex);
                    } else {
                        // 条目头已写出，无法回滚。尽量正常收尾让 ZIP 结构仍然合法，
                        // 并把该文件登记为「内容不完整」，由说明清单告知用户单独重下。
                        try {
                            zos.closeEntry();
                        } catch (Exception ignore) {
                            // 流可能已经坏了，忽略即可
                        }
                        failed.add(entry.entryName() + "（传输中断，内容不完整）");
                        log.error("打包下载：写入过程中断，该文件内容不完整 fileId={}, name={}",
                                entry.fileId(), entry.entryName(), ex);
                    }
                } finally {
                    closeQuietly(in);
                }

                // 每个文件结束后冲刷一次：同步模式下浏览器能尽早开始接收，
                // 异步模式下也能让进度更及时（虽然落盘本身不看这个）
                zos.flush();
                out.flush();
                if (listener != null) {
                    listener.onEntry(entry.entryName(), writtenFiles, writtenBytes, plan.totalBytes());
                }
            }

            // 有跳过或失败时，往包里写一份说明，避免用户以为文件凭空少了
            if (!skipped.isEmpty() || !failed.isEmpty() || plan.skippedCount() > 0) {
                writeManifest(zos, plan, skipped, failed);
            }
        } finally {
            try {
                // finish() 写出中央目录并结束 ZIP；必须在所有条目写完之后调用
                zos.finish();
            } catch (Exception e) {
                log.warn("打包下载：收尾写入 ZIP 中央目录失败（客户端可能已断开）", e);
            }
            try {
                zos.close();
            } catch (Exception ignore) {
                // 客户端提前断开时 close 会抛，属正常情况
            }
        }

        return new ZipResult(writtenFiles, writtenBytes, skipped, failed, plan.rootName());
    }

    private void writeDirectoryEntry(ZipOutputStream zos, String entryName) throws IOException {
        String name = entryName.endsWith("/") ? entryName : entryName + "/";
        zos.putNextEntry(new ZipEntry(name));
        zos.closeEntry();
    }

    /**
     * 写入说明清单
     *
     * <p>用 ZIP 内的一个文本条目来传达「哪些文件被跳过/不完整」，
     * 因为响应头早已发出，此时无法再改状态码或返回 JSON。
     */
    private void writeManifest(ZipOutputStream zos, ZipPlan plan,
                               List<String> skipped, List<String> failed) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("DMS 打包下载说明\r\n");
        sb.append("========================================\r\n");
        sb.append("打包时间：").append(LocalDateTime.now().format(TIME_FMT)).append("\r\n");
        sb.append("来源：").append(plan.rootName()).append("\r\n");
        sb.append("文件数：").append(plan.fileCount()).append(" 个\r\n");
        sb.append("原始大小：").append(humanSize(plan.totalBytes())).append("\r\n");
        sb.append("\r\n");

        if (plan.skippedCount() > 0) {
            sb.append("【未包含】因没有下载权限而被跳过的文件：").append(plan.skippedCount()).append(" 个\r\n");
            sb.append("  说明：ZIP 内只包含您有权下载的内容。如需完整资料，请联系管理员调整权限。\r\n\r\n");
        }
        if (!skipped.isEmpty()) {
            sb.append("【跳过】读取失败的文件（未写入）：").append(skipped.size()).append(" 个\r\n");
            for (String s : skipped) {
                sb.append("  - ").append(s).append("\r\n");
            }
            sb.append("\r\n");
        }
        if (!failed.isEmpty()) {
            sb.append("【不完整】以下文件内容不完整，请单独重新下载：").append(failed.size()).append(" 个\r\n");
            for (String s : failed) {
                sb.append("  - ").append(s).append("\r\n");
            }
            sb.append("\r\n");
        }
        sb.append("若对结果有疑问，请把本文件内容一并反馈给系统管理员。\r\n");

        zos.putNextEntry(new ZipEntry(MANIFEST_NAME));
        zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    /**
     * 清洗 ZIP 条目名
     *
     * <p>ZIP 条目名是路径，必须防住「路径穿越」（../）与绝对路径，
     * 同时去掉 Windows 不允许的字符，避免解压端报错。
     */
    private String sanitizeSegment(String raw) {
        String name = raw == null ? "" : raw.trim();
        // 统一分隔符并去掉路径成分，只保留最后一段
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // 去掉控制字符与 Windows 非法字符
        name = name.replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("[\\\\:*?\"<>|]", "_");
        // "." / ".." / 空 都是危险或非法条目名
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "unnamed";
        }
        // ZIP 条目名过长会让部分解压工具失败，留出余量
        if (name.length() > 120) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 12) {
                ext = name.substring(dot);
            }
            name = name.substring(0, Math.max(1, 120 - ext.length())) + ext;
        }
        return name;
    }

    /** 同一目录下重名时补 (1)(2)…，避免 ZIP 里出现重复条目 */
    private String uniqueName(Set<String> used, String name) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext;
    }

    private void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignore) {
                // 关闭失败不影响结果
            }
        }
    }

    /** 取最内层异常信息：MinIO 的顶层异常往往只有一句无用的包装 */
    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
    }

    /** 人类可读的大小 */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, units[i]);
    }
}

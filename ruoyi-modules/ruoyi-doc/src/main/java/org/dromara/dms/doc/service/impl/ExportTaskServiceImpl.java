package org.dromara.dms.doc.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocExportTask;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.mapper.DocExportTaskMapper;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.service.AuditService;
import org.dromara.dms.doc.service.ExportTaskService;
import org.dromara.dms.doc.service.ZipDownloadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 异步打包下载服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportTaskServiceImpl implements ExportTaskService {

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /** 进度落库的最小间隔：每写一个文件就 UPDATE 一次太费，按时间节流 */
    private static final long PROGRESS_INTERVAL_MS = 800L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DocExportTaskMapper taskMapper;
    private final DocFileMapper fileMapper;
    private final ZipDownloadService zipDownloadService;
    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;
    private final AuditService auditService;

    /** ZIP 落盘目录 */
    @Value("${dms.download.zip.async-dir:/opt/dms/export}")
    private String exportDir;

    /** 成品保留小时数，到点由定时任务删除 */
    @Value("${dms.download.zip.async-retention-hours:24}")
    private int retentionHours;

    /** 单用户同时进行中的任务数上限 */
    @Value("${dms.download.zip.async-max-active-per-user:2}")
    private int maxActivePerUser;

    // ==================================================================
    // 提交
    // ==================================================================

    @Override
    public DocExportTask submitFolder(Long folderId, Long userId) {
        return submit(List.of(folderId), List.of(), userId, "FOLDER", folderId);
    }

    @Override
    public DocExportTask submitSelection(List<Long> folderIds, List<Long> fileIds, Long userId) {
        return submit(folderIds, fileIds, userId, "SELECTION", null);
    }

    private DocExportTask submit(List<Long> folderIds, List<Long> fileIds, Long userId,
                                 String resourceType, Long resourceId) {
        if (taskMapper.countActiveByUser(userId) >= maxActivePerUser) {
            throw new ServiceException("您还有未完成的导出任务，请等它完成或先取消");
        }

        // 关键：在这里（有登录上下文）就把权限过滤做完并冻结成 JSON。
        // 后台线程没有 Sa-Token 上下文，绝不能在那里算权限。
        ZipDownloadService.ZipPlan plan = zipDownloadService.plan(folderIds, fileIds, userId);
        if (!plan.hasFiles()) {
            throw new ServiceException("这里没有您可下载的文件（可能需要「下载」权限）");
        }

        String planJson = freezePlan(plan, folderIds, fileIds);
        LocalDateTime now = LocalDateTime.now();
        DocExportTask task = new DocExportTask()
                .setUserId(userId)
                .setRootName(plan.rootName())
                .setResourceType(resourceType)
                .setResourceId(resourceId)
                .setStatus(DocExportTask.PENDING)
                .setFileCount(plan.fileCount())
                .setTotalBytes(plan.totalBytes())
                .setDoneFiles(0)
                .setDoneBytes(0L)
                .setZipBytes(0L)
                .setSkippedCount(plan.skippedCount())
                .setPlanJson(planJson)
                .setCreateTime(now)
                .setDownloadCount(0);
        taskMapper.insert(task);

        log.info("已提交异步打包任务: taskId={}, user={}, 根目录={}, 文件数={}, 大小={} 字节",
                task.getTaskId(), userId, plan.rootName(), plan.fileCount(), plan.totalBytes());
        return task;
    }

    /**
     * 把打包计划冻结成 JSON
     *
     * <p>只存「目录条目名」与「文件条目名 + fileId」，不存 bucket/storageKey/size——
     * 那些在后台线程按 fileId 批量查回来即可，JSON 体积能小一半以上。
     */
    private String freezePlan(ZipDownloadService.ZipPlan plan, List<Long> folderIds, List<Long> fileIds) {
        ObjectNode root = JSON.createObjectNode();
        root.put("rootName", plan.rootName());
        // 记录"原始请求"（用户点的文件夹/文件），用于下载前重算权限做比对。
        // 注意别把展开后的文件清单当成请求存进来 —— 那样复核时条件永远不成立，
        // 等于权限复核形同虚设（这个坑踩过）。
        ArrayNode reqFolders = root.putArray("reqFolderIds");
        folderIds.forEach(fid -> reqFolders.add(String.valueOf(fid)));
        ArrayNode reqFiles = root.putArray("reqFileIds");
        fileIds.forEach(fid -> reqFiles.add(String.valueOf(fid)));
        ArrayNode dirs = root.putArray("dirs");
        ArrayNode files = root.putArray("files");
        for (ZipDownloadService.ZipEntryPlan e : plan.entries()) {
            if (e.directory()) {
                dirs.add(e.entryName());
            } else {
                ObjectNode f = files.addObject();
                // fileId 以字符串存：雪花 ID 超出 JS 安全整数范围，统一按字符串处理
                f.put("id", String.valueOf(e.fileId()));
                f.put("name", e.entryName());
            }
        }
        return root.toString();
    }

    // ==================================================================
    // 查询 / 取消 / 删除
    // ==================================================================

    @Override
    public List<DocExportTask> listMine(Long userId, int limit) {
        return taskMapper.lambda()
                .eq(DocExportTask::getUserId, userId)
                .orderByDesc(DocExportTask::getTaskId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)))
                .list();
    }

    @Override
    public void cancel(Long taskId, Long userId) {
        DocExportTask task = requireOwned(taskId, userId);
        if (!DocExportTask.PENDING.equals(task.getStatus())) {
            throw new ServiceException("任务已开始执行，无法取消");
        }
        taskMapper.updateById(new DocExportTask()
                .setTaskId(taskId)
                .setStatus(DocExportTask.CANCELED)
                .setFinishTime(LocalDateTime.now()));
    }

    @Override
    public void delete(Long taskId, Long userId) {
        DocExportTask task = requireOwned(taskId, userId);
        if (DocExportTask.RUNNING.equals(task.getStatus())) {
            throw new ServiceException("任务正在执行，无法删除");
        }
        deleteArtifact(task);
        taskMapper.deleteById(taskId);
    }

    private DocExportTask requireOwned(Long taskId, Long userId) {
        DocExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("导出任务不存在");
        }
        // 超管可见全部，其余人只能操作自己的任务
        if (!LoginHelper.isSuperAdmin() && !userId.equals(task.getUserId())) {
            throw new ServiceException("无权操作他人的导出任务");
        }
        return task;
    }

    private void deleteArtifact(DocExportTask task) {
        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(task.getFilePath()));
        } catch (Exception e) {
            log.warn("删除导出文件失败: taskId={}, path={}", task.getTaskId(), task.getFilePath(), e);
        }
    }

    // ==================================================================
    // 后台打包
    // ==================================================================

    @Override
    public void runPackage(Long taskId) {
        DocExportTask task = taskMapper.selectById(taskId);
        if (task == null || !DocExportTask.RUNNING.equals(task.getStatus())) {
            return;
        }
        Path part = null;
        Path target = null;
        try {
            Path dir = Paths.get(exportDir);
            Files.createDirectories(dir);
            String fileName = "export-" + taskId + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
            target = dir.resolve(fileName);
            // 先写 .part，成功后再改名：避免"半成品 ZIP 被当成完成的文件下载"
            part = dir.resolve(fileName + ".part");

                JsonNode planNode = JSON.readTree(task.getPlanJson());
            List<String> dirs = new ArrayList<>();
            planNode.path("dirs").forEach(n -> dirs.add(n.asText()));
            Map<Long, String> fileNames = new java.util.LinkedHashMap<>();
            planNode.path("files").forEach(n -> fileNames.put(Long.valueOf(n.path("id").asText()), n.path("name").asText()));

            // 一次性把文件元数据查回来（后台线程里逐个查会 N+1）
            Map<Long, DocFile> files = new HashMap<>();
            if (!fileNames.isEmpty()) {
                for (DocFile f : fileMapper.selectByIds(fileNames.keySet())) {
                    files.put(f.getFileId(), f);
                }
            }

            int totalFiles = fileNames.size();
            long totalBytes = task.getTotalBytes() == null ? 0L : task.getTotalBytes();
            int[] written = new int[]{0};
            long[] writtenBytes = new long[]{0L};
            List<String> missing = new ArrayList<>();
            long[] lastProgressAt = new long[]{System.currentTimeMillis()};

            try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(part), COPY_BUFFER_SIZE);
                 ZipOutputStream zos = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                zos.setLevel(Deflater.NO_COMPRESSION);

                for (String d : dirs) {
                    zos.putNextEntry(new ZipEntry(d));
                    zos.closeEntry();
                }

                byte[] buf = new byte[COPY_BUFFER_SIZE];
                for (Map.Entry<Long, String> e : fileNames.entrySet()) {
                    DocFile f = files.get(e.getKey());
                    if (f == null || f.getDeletedAt() != null
                            || f.getStorageKey() == null || f.getStorageKey().isBlank()) {
                        // 提交之后被删掉了：跳过并记录，不让整个任务失败
                        missing.add(e.getValue());
                        continue;
                    }
                    InputStream in = null;
                    try {
                        in = minioClient.getObject(GetObjectArgs.builder()
                                .bucket(f.getStorageBucket() == null ? minIoConfig.getBucket() : f.getStorageBucket())
                                .object(f.getStorageKey())
                                .build());
                        zos.putNextEntry(new ZipEntry(e.getValue()));
                        int n;
                        while ((n = in.read(buf, 0, buf.length)) > 0) {
                            zos.write(buf, 0, n);
                            writtenBytes[0] += n;
                        }
                        zos.closeEntry();
                        written[0]++;
                    } catch (Exception ex) {
                        // 单个文件失败不中断整个任务（与同步打包行为一致）
                        missing.add(e.getValue() + "（读取失败）");
                        log.error("异步打包：读取文件失败 taskId={}, fileId={}", taskId, e.getKey(), ex);
                        try {
                            zos.closeEntry();
                        } catch (Exception ignore) {
                            // 流可能已坏
                        }
                    } finally {
                        closeQuietly(in);
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastProgressAt[0] >= PROGRESS_INTERVAL_MS || written[0] == totalFiles) {
                        lastProgressAt[0] = now;
                        // 进度落库：前端据此显示真实百分比（同步流式模式做不到）
                        taskMapper.updateProgress(taskId, written[0], writtenBytes[0]);
                    }
                }

                if (!missing.isEmpty()) {
                    writeManifest(zos, task, missing);
                }
            }

            long zipBytes = Files.size(part);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            part = null;

            LocalDateTime now = LocalDateTime.now();
            taskMapper.updateById(new DocExportTask()
                    .setTaskId(taskId)
                    .setStatus(DocExportTask.SUCCESS)
                    .setDoneFiles(written[0])
                    .setDoneBytes(writtenBytes[0])
                    .setZipBytes(zipBytes)
                    .setFilePath(target.toString())
                    .setFinishTime(now)
                    .setExpireTime(now.plusHours(retentionHours)));

            log.info("异步打包完成: taskId={}, 文件={}/{}, 压缩包={} 字节, 跳过={}",
                    taskId, written[0], totalFiles, zipBytes, missing.size());

            // 后台线程没有请求上下文，这里传入显式 userId
            auditService.recordAs(task.getUserId(), AuditAction.DOWNLOAD, "EXPORT_TASK", taskId,
                    task.getRootName(), Map.of(
                            "mode", "async",
                            "writtenFiles", written[0],
                            "writtenBytes", writtenBytes[0],
                            "zipBytes", zipBytes,
                            "skippedOrFailed", missing.size()));

        } catch (Exception e) {
            log.error("异步打包失败: taskId={}", taskId, e);
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (Exception ignore) {
                    // 清理失败不影响主流程
                }
            }
            taskMapper.updateById(new DocExportTask()
                    .setTaskId(taskId)
                    .setStatus(DocExportTask.FAILED)
                    .setErrorMsg(truncate(e.getMessage(), 900))
                    .setFinishTime(LocalDateTime.now()));
        }
    }

    @Override
    public void markFailed(Long taskId, String reason) {
        taskMapper.updateById(new DocExportTask()
                .setTaskId(taskId)
                .setStatus(DocExportTask.FAILED)
                .setErrorMsg(truncate(reason, 900))
                .setFinishTime(LocalDateTime.now()));
    }

    private void writeManifest(ZipOutputStream zos, DocExportTask task, List<String> missing) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("DMS 打包下载说明\r\n");
        sb.append("========================================\r\n");
        sb.append("来源：").append(task.getRootName()).append("\r\n");
        sb.append("打包方式：后台异步导出\r\n");
        sb.append("\r\n");
        sb.append("【未包含】以下条目在打包过程中已不可读取（可能已被删除或移动）：\r\n");
        sb.append("共 ").append(missing.size()).append(" 个\r\n");
        for (String s : missing) {
            sb.append("  - ").append(s).append("\r\n");
        }
        sb.append("\r\n若对结果有疑问，请把本文件内容反馈给系统管理员。\r\n");
        zos.putNextEntry(new ZipEntry("_下载说明.txt"));
        zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ==================================================================
    // 下载成品
    // ==================================================================

    @Override
    public void download(Long taskId, Long userId, HttpServletResponse response) throws IOException {
        DocExportTask task = requireOwned(taskId, userId);
        if (!DocExportTask.SUCCESS.equals(task.getStatus())) {
            throw new ServiceException("该导出任务还没有完成");
        }
        if (task.getExpireTime() != null && task.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException("该导出文件已过期，请重新导出");
        }
        if (task.getFilePath() == null || !Files.exists(Paths.get(task.getFilePath()))) {
            throw new ServiceException("导出文件已不存在，请重新导出");
        }

        // 权限复核：打包计划是"提交那一刻的快照"，如果之后权限被收紧，
        // 现在能下载的文件会比冻结时少 —— 那就不能让用户拿走旧快照。
        verifyStillPermitted(task, userId);

        Path path = Paths.get(task.getFilePath());
        String zipName = task.getRootName() + ".zip";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        String encoded = URLEncoder.encode(zipName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"download.zip\"; filename*=UTF-8''" + encoded);
        response.setContentLengthLong(Files.size(path));
        response.setHeader("Cache-Control", "no-store");

        try (InputStream in = Files.newInputStream(path);
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }

        taskMapper.updateById(new DocExportTask()
                .setTaskId(taskId)
                .setDownloadCount((task.getDownloadCount() == null ? 0 : task.getDownloadCount()) + 1)
                .setLastDownloadTime(LocalDateTime.now()));
    }

    /**
     * 用当前权限重算一次计划，与冻结时的文件数比较
     *
     * <p>现在少于当初 → 说明有人收紧了权限，拒绝下发旧快照。
     * 现在多于当初 → 只是期间新增了文件，旧快照仍是他有权下载的内容，允许下载。
     */
    private void verifyStillPermitted(DocExportTask task, Long userId) {
        List<Long> folderIds = new ArrayList<>();
        List<Long> fileIds = new ArrayList<>();
        try {
            JsonNode node = JSON.readTree(task.getPlanJson());
            node.path("reqFolderIds").forEach(n -> folderIds.add(Long.valueOf(n.asText())));
            node.path("reqFileIds").forEach(n -> fileIds.add(Long.valueOf(n.asText())));
        } catch (Exception e) {
            log.warn("解析导出任务计划失败，跳过权限复核: taskId={}", task.getTaskId(), e);
            return;
        }
        if (folderIds.isEmpty() && fileIds.isEmpty()) {
            return;
        }
        try {
            // 用"当前"权限重算一次：如果现在能下的文件比冻结时少，
            // 说明期间有人收紧了权限，不能把旧快照发出去
            ZipDownloadService.ZipPlan now = zipDownloadService.plan(folderIds, fileIds, userId);
            int frozen = task.getFileCount() == null ? 0 : task.getFileCount();
            if (now.fileCount() < frozen) {
                log.warn("导出任务权限复核未通过: taskId={}, 冻结时={} 个文件, 当前={} 个",
                        task.getTaskId(), frozen, now.fileCount());
                throw new ServiceException("您的下载权限已变更，导出内容可能不再完整，请重新导出");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("导出任务权限复核异常，放行: taskId={}", task.getTaskId(), e);
        }
    }

    private void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignore) {
                // 忽略
            }
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

package org.dromara.dms.doc.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocPermissionQueryMapper;
import org.dromara.dms.doc.service.DocNameService;
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.service.PermissionScopeResolver;
import org.dromara.dms.doc.service.PermissionService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文件服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final DocFileMapper fileMapper;
    private final DocPermissionQueryMapper permQueryMapper;
    private final PermissionScopeResolver scopeResolver;
    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;
    private final DocNameService nameService;
    private final PermissionService permissionService;

    @Override
    public IPage<DocFile> pageByFolder(Long folderId, int page, int size, Long userId) {
        Page<DocFile> pageParam = new Page<>(page, size);
        PermissionScopeResolver.Scope scope = scopeResolver.current();
        // 超级管理员不受权限限制
        if (scope.unrestricted()) {
            IPage<DocFile> all = fileMapper.pageByFolder(pageParam, folderId);
            fillUserFlags(all.getRecords());
            return all;
        }
        // 文件夹本身不可见 → 该目录下不返回任何文件
        List<Long> readableFolders = permQueryMapper.selectReadableFolderIds(
                scope.userId(), scope.roleIds(), scope.deptIds());
        if (!readableFolders.contains(folderId)) {
            pageParam.setRecords(List.of());
            pageParam.setTotal(0);
            return pageParam;
        }
        IPage<DocFile> pageResult = permQueryMapper.pageReadableFiles(pageParam, folderId, scope.userId(),
                sentinelIfEmpty(readableFolders), scope.roleIds(), scope.deptIds());
        fillUserFlags(pageResult.getRecords());
        return pageResult;
    }

    /**
     * 给文件列表填充「当前用户对该文件的权限位」
     *
     * <p>前端据此决定下载/改名/删除按钮是否可用。没有这一步，预览弹窗会一律按
     * 满权限渲染，只读用户看到一个点了就报 403 的下载按钮。
     */
    private void fillUserFlags(List<DocFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Map<Long, Integer> flags = permissionService.computeFileFlagsBatch(files, LoginHelper.getUserId());
        files.forEach(f -> f.setUserFlags(flags.getOrDefault(f.getFileId(), 0)));
    }

    @Override
    public DocFile getById(Long fileId, Long userId) {
        DocFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        fillUserFlags(List.of(file));
        return file;
    }

    @Override
    public void rename(Long fileId, String newName, Long userId) {
        if (newName == null || newName.isBlank()) {
            throw new ServiceException("文件名不能为空");
        }
        String name = newName.trim();
        DocFile current = mustGet(fileId);
        // 与原名字相同视为无改动直接返回，避免误报「已存在同名文件」
        if (name.equals(current.getFileName())) {
            return;
        }
        // 重命名是用户显式指定的名字，同名时报错而不是自动加 (1)
        if (nameService.fileNameExists(current.getFolderId(), name)) {
            throw new ServiceException("当前文件夹下已存在同名文件：「" + name + "」");
        }
        fileMapper.rename(fileId, name, LocalDateTime.now());
    }

    @Override
    public void move(Long fileId, Long targetFolderId, Long userId) {
        fileMapper.move(fileId, targetFolderId, LocalDateTime.now());
    }

    @Override
    public Long copy(Long fileId, Long targetFolderId, Long userId) {
        DocFile src = mustGet(fileId);
        if (src.getDeletedAt() != null) {
            throw new IllegalArgumentException("源文件已在回收站");
        }
        // 目标文件夹已有同名文件 → 自动生成「xxx (1).ext」，与 Windows 一致
        String copyName = nameService.uniqueFileName(targetFolderId, src.getFileName());
        DocFile copy = new DocFile()
                .setFolderId(targetFolderId)
                .setFileName(copyName)
                .setFileExtension(src.getFileExtension())
                .setFileSize(src.getFileSize())
                .setFileHash(src.getFileHash())
                .setMimeType(src.getMimeType())
                .setStorageBackend(src.getStorageBackend())
                .setStorageBucket(src.getStorageBucket())
                .setStorageKey(src.getStorageKey())
                .setPreviewKey(src.getPreviewKey())
                .setThumbnailKey(src.getThumbnailKey())
                .setPageCount(src.getPageCount())
                .setCreatorId(userId)
                .setCreateTime(LocalDateTime.now());
        fileMapper.insert(copy);
        log.info("File copied: src={} -> folder={}, newId={}, name={}",
                fileId, targetFolderId, copy.getFileId(), copyName);
        return copy.getFileId();
    }

    @Override
    public int moveBatch(List<Long> fileIds, Long targetFolderId, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (Long id : fileIds) {
            n += fileMapper.move(id, targetFolderId, now);
        }
        log.info("Moved {} files to folder {}", n, targetFolderId);
        return n;
    }

    @Override
    public void softDelete(Long fileId, Long userId) {
        fileMapper.softDelete(fileId, LocalDateTime.now());
    }

    @Override
    public void restore(Long fileId, Long userId) {
        fileMapper.restore(fileId);
    }

    @Override
    public void hardDelete(Long fileId, Long userId) {
        fileMapper.hardDelete(fileId);
    }

    @Override
    public List<DocFile> search(String keyword, int limit, Long userId) {
        PermissionScopeResolver.Scope scope = scopeResolver.current();
        if (scope.unrestricted()) {
            List<DocFile> hits = fileMapper.fulltextSearch(keyword, limit);
            fillUserFlags(hits);
            return hits;
        }
        List<Long> readableFolders = permQueryMapper.selectReadableFolderIds(
                scope.userId(), scope.roleIds(), scope.deptIds());
        return permQueryMapper.searchReadableFiles(keyword, limit, scope.userId(),
                sentinelIfEmpty(readableFolders), scope.roleIds(), scope.deptIds());
    }

    /**
     * IN 子句不接受空集合，空时用 -1 占位（确保不命中任何行）
     */
    private List<Long> sentinelIfEmpty(List<Long> ids) {
        return (ids == null || ids.isEmpty()) ? List.of(-1L) : ids;
    }

    @Override
    public void download(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        DocFile file = mustGet(fileId);
        streamFromMinIo(file, request, response, true);
    }

    @Override
    public void streamContent(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        DocFile file = mustGet(fileId);
        // 有 preview_key（Office 转的 PDF / 视频转的 mp4）则输出预览版；否则输出原文件。
        // Content-Type 必须按预览文件的真实类型推断：写死 application/pdf 会让转码后的
        // mp4 以 PDF 类型下发，浏览器 <video> 直接拒绝播放。
        if (file.getPreviewKey() != null && !file.getPreviewKey().isBlank()) {
            streamKeyWithMime(file, file.getStorageBucket(), file.getPreviewKey(),
                    guessMime(file.getPreviewKey(), "application/pdf"), request, response, false);
        } else {
            streamFromMinIo(file, request, response, false);
        }
    }

    @Override
    public void streamThumbnail(Long fileId, HttpServletResponse response) throws IOException {
        DocFile file = mustGet(fileId);
        String bucket = file.getStorageBucket();
        String key = file.getThumbnailKey();
        if (key == null || key.isBlank()) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        writeFull(bucket, key, response, guessMime(key, "image/png"), false);
    }

    /**
     * 按对象 key 的扩展名推断 MIME
     *
     * <p>预览版/缩略图的类型和原文件不同（Office→PDF、视频→MP4、视频海报→JPEG），
     * 不能用原文件的 mime_type，只能从 key 后缀判断。
     */
    private String guessMime(String key, String fallback) {
        if (key == null) {
            return fallback;
        }
        String k = key.toLowerCase();
        if (k.endsWith(".pdf")) return "application/pdf";
        if (k.endsWith(".mp4")) return "video/mp4";
        if (k.endsWith(".webm")) return "video/webm";
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) return "image/jpeg";
        if (k.endsWith(".png")) return "image/png";
        if (k.endsWith(".webp")) return "image/webp";
        if (k.endsWith(".gif")) return "image/gif";
        return fallback;
    }

    /**
     * 从 MinIO 流式输出，支持 HTTP Range（下载 206 / 预览分块）
     */
    private void streamFromMinIo(DocFile file, HttpServletRequest request,
                                 HttpServletResponse response, boolean asAttachment) throws IOException {
        String bucket = file.getStorageBucket();
        String key = file.getStorageKey();
        if (key == null || key.isBlank()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long totalSize;
        String contentType = "application/octet-stream";
        try {
            var stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(key).build());
            totalSize = stat.size();
            if (file.getMimeType() != null && !file.getMimeType().isBlank()) {
                contentType = file.getMimeType();
            }
        } catch (Exception e) {
            log.error("statObject failed: bucket={}, key={}", bucket, key, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // Content-Disposition
        if (asAttachment) {
            String encoded = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        } else {
            String encoded = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" + encoded);
        }

        // Range 解析
        String rangeHeader = request.getHeader("Range");
        long start = 0;
        long end = totalSize - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            if (!spec.isBlank()) {
                String[] parts = spec.split("-", 2);
                try {
                    if (!parts[0].isBlank()) {
                        start = Long.parseLong(parts[0]);
                    }
                    if (parts.length > 1 && !parts[1].isBlank()) {
                        end = Math.min(Long.parseLong(parts[1]), totalSize - 1);
                    } else {
                        end = totalSize - 1;
                    }
                    partial = true;
                } catch (NumberFormatException ignore) {
                    partial = false;
                }
            }
        }
        if (start > end || start < 0) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + totalSize);
            return;
        }
        long length = end - start + 1;

        response.setStatus(partial ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setHeader("Accept-Ranges", "bytes");
        if (partial) {
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + totalSize);
        }
        response.setContentType(contentType);
        response.setContentLengthLong(length);

        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(key).offset(start).length(length).build());
             OutputStream os = response.getOutputStream()) {
            is.transferTo(os);
        } catch (Exception e) {
            log.error("stream failed: bucket={}, key={}", bucket, key, e);
        }
    }

    /**
     * 整文件输出（缩略图）
     */
    private void writeFull(String bucket, String key, HttpServletResponse response,
                           String contentType, boolean asAttachment) throws IOException {
        long size;
        try {
            var stat = minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            size = stat.size();
        } catch (Exception e) {
            log.error("statObject failed: bucket={}, key={}", bucket, key, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        response.setContentType(contentType);
        response.setContentLengthLong(size);
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(key).build());
             OutputStream os = response.getOutputStream()) {
            is.transferTo(os);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("writeFull stream failed: bucket={}, key={}", bucket, key, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 流式输出指定 key（带 Content-Disposition 文件名），支持 Range
     */
    private void streamKeyWithMime(DocFile file, String bucket, String key, String contentType,
                                   HttpServletRequest request, HttpServletResponse response,
                                   boolean asAttachment) throws IOException {
        long totalSize;
        try {
            var stat = minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            totalSize = stat.size();
        } catch (Exception e) {
            log.error("statObject failed: bucket={}, key={}", bucket, key, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        String encoded = URLEncoder.encode(
                asAttachment ? (file.getFileName() == null ? "download" : file.getFileName())
                        : (file.getFileName() == null ? "preview" : file.getFileName()),
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                (asAttachment ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded);

        String rangeHeader = request.getHeader("Range");
        long start = 0;
        long end = totalSize - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            if (!spec.isBlank()) {
                String[] parts = spec.split("-", 2);
                try {
                    if (!parts[0].isBlank()) start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isBlank()) {
                        end = Math.min(Long.parseLong(parts[1]), totalSize - 1);
                    }
                    partial = true;
                } catch (NumberFormatException ignore) {
                    partial = false;
                }
            }
        }
        if (start > end || start < 0) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + totalSize);
            return;
        }
        long length = end - start + 1;
        response.setStatus(partial ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setHeader("Accept-Ranges", "bytes");
        if (partial) {
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + totalSize);
        }
        response.setContentType(contentType);
        response.setContentLengthLong(length);
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(key).offset(start).length(length).build());
             OutputStream os = response.getOutputStream()) {
            is.transferTo(os);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("streamKeyWithMime failed: bucket={}, key={}", bucket, key, e);
        }
    }

    private DocFile mustGet(Long fileId) {
        DocFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return file;
    }
}

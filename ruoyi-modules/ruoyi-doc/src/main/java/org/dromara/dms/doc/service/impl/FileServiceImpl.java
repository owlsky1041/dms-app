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
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.service.FileService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

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
    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;

    @Override
    public IPage<DocFile> pageByFolder(Long folderId, int page, int size, Long userId) {
        return fileMapper.pageByFolder(new Page<>(page, size), folderId);
    }

    @Override
    public DocFile getById(Long fileId, Long userId) {
        DocFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return file;
    }

    @Override
    public void rename(Long fileId, String newName, Long userId) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        fileMapper.rename(fileId, newName, LocalDateTime.now());
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
        DocFile copy = new DocFile()
                .setFolderId(targetFolderId)
                .setFileName(src.getFileName())
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
        log.info("File copied: src={} -> folder={}, newId={}", fileId, targetFolderId, copy.getFileId());
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
        return fileMapper.fulltextSearch(keyword, limit);
    }

    @Override
    public void download(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        DocFile file = mustGet(fileId);
        streamFromMinIo(file, request, response, true);
    }

    @Override
    public void streamContent(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        DocFile file = mustGet(fileId);
        // 有 preview_key（如 Office 转换的 PDF）则输出预览版；否则输出原文件
        if (file.getPreviewKey() != null && !file.getPreviewKey().isBlank()) {
            streamKeyWithMime(file, file.getStorageBucket(), file.getPreviewKey(),
                    "application/pdf", request, response, false);
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
        writeFull(bucket, key, response, "image/png", false);
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

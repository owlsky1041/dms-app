package org.dromara.dms.doc.service.impl;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.UploadInfo;
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.service.FileProcessor;
import org.dromara.dms.doc.service.UploadCompletionDelegate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 上传完成回调实现（v1.0 简化版）
 *
 * <p>流程：
 * <ol>
 *   <li>读取 tus 元数据（metadata 包含 userId/folderId/fileName/fileHash）</li>
 *   <li>读 tus 分块字节流</li>
 *   <li>计算 SHA-256（如客户端未传）</li>
 *   <li>查重（秒传）：若 file_hash 已存在，复用现有 doc_file 记录</li>
 *   <li>上传到 MinIO</li>
 *   <li>INSERT doc_file 记录</li>
 *   <li>触发 FileProcessor.processAsync()（异步生成缩略图/预览/文本）</li>
 *   <li>删除 tus 临时分块</li>
 * </ol>
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadCompletionDelegateImpl implements UploadCompletionDelegate {

    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;
    private final DocFileMapper fileMapper;
    private final FileProcessor fileProcessor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onComplete(UploadInfo uploadInfo, TusFileUploadService service) {
        String uploadId = uploadInfo.getId().toString();
        Map<String, String> metadata = uploadInfo.getMetadata();
        String fileName = metadata.get("filename");
        String folderIdStr = metadata.get("folderId");
        String userIdStr = metadata.get("userId");
        String clientHash = metadata.get("fileHash");  // 客户端可选传 SHA-256（Web Crypto）

        if (fileName == null || folderIdStr == null || userIdStr == null) {
            log.error("Missing required metadata for upload {}: filename={}, folderId={}, userId={}",
                    uploadId, fileName, folderIdStr, userIdStr);
            throw new IllegalArgumentException("上传元数据缺失");
        }

        Long folderId = Long.parseLong(folderIdStr);
        Long userId = Long.parseLong(userIdStr);
        String fileExtension = extractExtension(fileName);

        try {
            // 1. 秒传检查
            String finalHash = clientHash;
            if (finalHash == null) {
                // 计算 SHA-256（注意必须传 ownerKey=userId，否则 tus 查不到上传）
                finalHash = computeHash(service, uploadId, String.valueOf(userId));
            }

            DocFile existing = fileMapper.findByHash(finalHash);
            if (existing != null) {
                log.info("Instant upload: file already exists, hash={}, existingId={}", finalHash, existing.getFileId());
                // 直接复用，复制引用到当前 folder
                DocFile ref = cloneForFolder(existing, folderId, userId);
                fileMapper.insert(ref);
                service.deleteUpload(uploadId, String.valueOf(userId));
                return;
            }

            // 2. 上传到 MinIO
            String objectKey = String.format("files/%d/%d/%s-%s",
                    folderId, System.currentTimeMillis(), UUID.randomUUID(), sanitizeName(fileName));

            try (InputStream is = service.getUploadedBytes(uploadId, String.valueOf(userId))) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minIoConfig.getBucket())
                        .object(objectKey)
                        .stream(is, uploadInfo.getLength(), -1)
                        .contentType(uploadInfo.getMetadata().getOrDefault("mimeType", "application/octet-stream"))
                        .build());
            }

            // 3. 写 doc_file
            DocFile file = new DocFile()
                    .setFolderId(folderId)
                    .setFileName(fileName)
                    .setFileExtension(fileExtension)
                    .setFileSize(uploadInfo.getLength())
                    .setFileHash(finalHash)
                    .setMimeType(uploadInfo.getMetadata().get("mimeType"))
                    .setStorageBackend("minio")
                    .setStorageBucket(minIoConfig.getBucket())
                    .setStorageKey(objectKey)
                    .setCreatorId(userId)
                    .setCreateTime(LocalDateTime.now());
            fileMapper.insert(file);

            log.info("File uploaded: id={}, name={}, size={}, hash={}",
                    file.getFileId(), fileName, file.getFileSize(), finalHash);

            // 4. 异步处理（缩略图/预览/文本提取）
            fileProcessor.processAsync(file);

            // 5. 清理 tus 临时文件
            try {
                service.deleteUpload(uploadId, String.valueOf(userId));
            } catch (Exception e) {
                log.warn("Failed to delete tus upload {} (will be cleaned by expiration)", uploadId, e);
            }

        } catch (Exception e) {
            log.error("Failed to process upload complete: uploadId={}", uploadId, e);
            throw new RuntimeException("上传完成处理失败", e);
        }
    }

    /**
     * 从 InputStream 计算 SHA-256
     *
     * @param ownerKey tus 上传隔离 key（=userId），读取上传字节必须传，否则 UploadNotFound
     */
    private String computeHash(TusFileUploadService service, String uploadId, String ownerKey) {
        try (InputStream is = service.getUploadedBytes(uploadId, ownerKey)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算 SHA-256 失败", e);
        }
    }

    private DocFile cloneForFolder(DocFile src, Long newFolderId, Long userId) {
        return new DocFile()
                .setFolderId(newFolderId)
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
    }

    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

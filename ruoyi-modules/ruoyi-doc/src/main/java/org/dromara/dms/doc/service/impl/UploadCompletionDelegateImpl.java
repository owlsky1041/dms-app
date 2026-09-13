package org.dromara.dms.doc.service.impl;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.UploadInfo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.FileProcessor;
import org.dromara.dms.doc.service.DocFolderOwnerResolver;
import org.dromara.dms.doc.service.AuditService;
import org.dromara.dms.doc.service.DocNameService;
import org.dromara.dms.doc.service.InstantUploadService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.dms.doc.service.UploadCompletionDelegate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 上传完成回调实现
 *
 * <p>流程：
 * <ol>
 *   <li>读取 tus 元数据（folderId / fileName / relativePath 等）</li>
 *   <li>若带 relativePath（文件夹上传），自动逐级创建/查找子文件夹</li>
 *   <li>读 tus 分块字节流 → 计算 SHA-256</li>
 *   <li>查重（秒传）</li>
 *   <li>上传到 MinIO → INSERT doc_file</li>
 *   <li>异步 FileProcessor（文本/预览）→ 删除 tus 临时文件</li>
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
    private final DocFolderMapper folderMapper;
    private final FileProcessor fileProcessor;
    private final InstantUploadService instantUploadService;
    private final PermissionService permissionService;
    private final DocFolderOwnerResolver ownerResolver;
    private final DocNameService nameService;
    private final AuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onComplete(UploadInfo uploadInfo, TusFileUploadService service, String uploadUrl, Long ownerUserId) {
        String uploadId = uploadInfo.getId().toString();
        Map<String, String> metadata = uploadInfo.getMetadata();
        String fileName = metadata.get("filename");
        String folderIdStr = metadata.get("folderId");

        if (fileName == null || folderIdStr == null || ownerUserId == null) {
            log.error("Missing required info for upload {}: filename={}, folderId={}, ownerUserId={}",
                    uploadId, fileName, folderIdStr, ownerUserId);
            throw new IllegalArgumentException("上传信息缺失");
        }

        Long folderId = Long.parseLong(folderIdStr);
        if (folderId == 0L) {
            throw new ServiceException("请在具体文件夹内上传文件（当前为文档根目录）");
        }
        // 上传者 = 后端登录用户（tus ownerKey），不信任前端 metadata.userId
        Long userId = ownerUserId;
        String ownerKey = String.valueOf(userId);
        String tusUrl = uploadUrl;
        String fileExtension = extractExtension(fileName);

        // 落库前最终权限校验（tus 建会话时已校验一次，此处覆盖文件夹上传等路径）
        permissionService.requireFolder(folderId, PermissionFlag.UPLOAD, userId);

        // 文件夹上传：按 relativePath（如 "设计图/施工图/a.pdf"）自动逐级建子文件夹
        String relativePath = metadata.get("relativePath");
        if (relativePath != null && !relativePath.isBlank()) {
            String dirPart = relativePath.substring(0, Math.max(0, relativePath.lastIndexOf('/')));
            if (!dirPart.isBlank()) {
                // 「创建子项」位已取消：文件夹上传自动建目录包含在「上传」权限内
                folderId = ensureFolderChain(folderId, dirPart, userId);
            }
        }

        try {
            // 1. 秒传检查
            String finalHash;
            // 计算 SHA-256（ownerKey=真实登录 id，否则 UploadNotFound）
            finalHash = computeHash(service, uploadUrl, ownerKey);

            // 目标文件夹已有同名文件 → 自动重命名为「xxx (1).ext」
            // 注意要放在秒传分支之前，让秒传引用和真实上传拿到同一个最终名字
            fileName = nameService.uniqueFileName(folderId, fileName);
            fileExtension = extractExtension(fileName);

            DocFile existing = fileMapper.findByHash(finalHash);
            if (existing != null) {
                log.info("Instant upload: file already exists, hash={}, existingId={}", finalHash, existing.getFileId());
                // 直接引用（与 /api/upload/instant 走同一实现），不重复上传 MinIO 对象
                instantUploadService.createReference(existing, folderId, userId, fileName);
                service.deleteUpload(uploadUrl, ownerKey);
                return;
            }

            // 2. 上传到 MinIO
            String objectKey = String.format("files/%d/%d/%s-%s",
                    folderId, System.currentTimeMillis(), UUID.randomUUID(), sanitizeName(fileName));

            try (InputStream is = service.getUploadedBytes(uploadUrl, ownerKey)) {
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
            auditService.record(AuditAction.UPLOAD, "FILE", file.getFileId(), fileName,
                    java.util.Map.of("folderId", String.valueOf(folderId),
                            "fileSize", String.valueOf(file.getFileSize()),
                            "hash", finalHash));

            // 4. 异步处理（缩略图/预览/文本提取）
            fileProcessor.processAsync(file);

            // 5. 清理 tus 临时文件
            try {
                service.deleteUpload(uploadUrl, ownerKey);
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
    private String computeHash(TusFileUploadService service, String uploadUrl, String ownerKey) {
        try (InputStream is = service.getUploadedBytes(uploadUrl, ownerKey)) {
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


    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 按相对路径（如 "设计图/施工图"）从 baseFolderId 目录下逐级查找或创建子文件夹
     *
     * @return 最深层子文件夹 id；无段时返回 baseFolderId
     */
    private Long ensureFolderChain(Long baseFolderId, String dirPath, Long userId) {
        String[] segments = dirPath.split("/");
        DocFolder current = folderMapper.selectById(baseFolderId);
        if (current == null) {
            return baseFolderId;
        }
        Long parentId = current.getFolderId();
        String parentPath = current.getFolderPath();

        for (String seg : segments) {
            if (seg == null || seg.isBlank()) continue;
            DocFolder found = findFolder(parentId, seg);
            if (found != null) {
                parentId = found.getFolderId();
                parentPath = found.getFolderPath();
            } else {
                DocFolder created = new DocFolder()
                        .setParentId(parentId)
                        .setFolderName(seg)
                        // 物化路径：父路径 + 父id + "/"
                        .setFolderPath(parentPath + parentId + "/")
                        // 所有者继承文档区所有者（与手动建目录保持一致）
                        .setOwnerId(ownerResolver.resolveOwner(parentId, userId))
                        .setSortOrder(0)
                        .setCreateBy(userId)
                        .setCreateTime(LocalDateTime.now());
                folderMapper.insert(created);
                log.info("Auto-created subfolder '{}' under parent={}", seg, parentId);
                parentId = created.getFolderId();
                parentPath = created.getFolderPath();
            }
        }
        return parentId;
    }

    /** 在 parentId 下按名称查找未删除子文件夹 */
    private DocFolder findFolder(Long parentId, String name) {
        return folderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocFolder>()
                        .eq("parent_id", parentId)
                        .eq("folder_name", name)
                        .isNull("deleted_at")
                        .last("LIMIT 1"));
    }
}

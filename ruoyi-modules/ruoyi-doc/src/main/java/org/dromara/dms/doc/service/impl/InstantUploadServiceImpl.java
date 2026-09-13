package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.service.DocNameService;
import org.dromara.dms.doc.service.InstantUploadService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 秒传（SHA-256 去重引用）实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstantUploadServiceImpl implements InstantUploadService {

    private final DocFileMapper fileMapper;
    private final DocNameService nameService;

    @Override
    public DocFile findByHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        return fileMapper.findByHash(hash.trim().toLowerCase());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocFile createReference(DocFile existing, Long targetFolder, Long userId, String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? existing.getFileName() : fileName;
        // 秒传引用同样要避开目标文件夹里的同名文件
        name = nameService.uniqueFileName(targetFolder, name);
        DocFile ref = new DocFile()
                .setFolderId(targetFolder)
                .setFileName(name)
                .setFileExtension(extractExtension(name))
                .setFileSize(existing.getFileSize())
                .setFileHash(existing.getFileHash())
                .setMimeType(existing.getMimeType())
                .setStorageBackend(existing.getStorageBackend())
                .setStorageBucket(existing.getStorageBucket())
                .setStorageKey(existing.getStorageKey())
                .setPreviewKey(existing.getPreviewKey())
                .setThumbnailKey(existing.getThumbnailKey())
                .setPageCount(existing.getPageCount())
                .setCreatorId(userId)
                .setCreateTime(LocalDateTime.now());
        fileMapper.insert(ref);
        log.info("Instant upload reference created: hash={}, srcFileId={}, newFileId={}, folder={}",
                existing.getFileHash(), existing.getFileId(), ref.getFileId(), targetFolder);
        return ref;
    }

    private String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}

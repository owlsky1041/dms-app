package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.service.DocNameService;
import org.springframework.stereotype.Service;

/**
 * 同名项命名服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocNameServiceImpl implements DocNameService {

    /**
     * 自动编号的最大尝试次数
     *
     * <p>正常场景远达不到；纯粹为了防止「查询结果与数据库不一致」之类的异常情况下死循环。
     */
    private static final int MAX_ATTEMPTS = 1000;

    private final DocFileMapper fileMapper;
    private final DocFolderMapper folderMapper;

    @Override
    public String uniqueFileName(Long folderId, String desiredName) {
        String name = normalize(desiredName);
        if (!fileNameExists(folderId, name)) {
            return name;
        }
        String[] parts = DocNameService.splitName(name);
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            String candidate = parts[0] + " (" + i + ")" + parts[1];
            if (!fileNameExists(folderId, candidate)) {
                log.info("同名文件自动重命名: 「{}」 -> 「{}」(folder={})", name, candidate, folderId);
                return candidate;
            }
        }
        String fallback = parts[0] + " (" + System.currentTimeMillis() + ")" + parts[1];
        log.warn("同名文件编号超过 {} 次，改用时间戳: 「{}」 -> 「{}」(folder={})",
                MAX_ATTEMPTS, name, fallback, folderId);
        return fallback;
    }

    @Override
    public String uniqueFolderName(Long parentId, String desiredName) {
        String name = normalize(desiredName);
        if (!folderNameExists(parentId, name)) {
            return name;
        }
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            String candidate = name + " (" + i + ")";
            if (!folderNameExists(parentId, candidate)) {
                log.info("同名文件夹自动重命名: 「{}」 -> 「{}」(parent={})", name, candidate, parentId);
                return candidate;
            }
        }
        String fallback = name + " (" + System.currentTimeMillis() + ")";
        log.warn("同名文件夹编号超过 {} 次，改用时间戳: 「{}」 -> 「{}」(parent={})",
                MAX_ATTEMPTS, name, fallback, parentId);
        return fallback;
    }

    @Override
    public boolean fileNameExists(Long folderId, String name) {
        if (folderId == null || name == null || name.isBlank()) {
            return false;
        }
        return fileMapper.existsByName(folderId, name);
    }

    @Override
    public boolean folderNameExists(Long parentId, String name) {
        if (parentId == null || name == null || name.isBlank()) {
            return false;
        }
        return folderMapper.existsByName(parentId, name);
    }

    private String normalize(String name) {
        return name == null ? "" : name.trim();
    }
}

package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.mapper.DocFolderPermissionMapper;
import org.dromara.dms.doc.mapper.DocPermissionQueryMapper;
import org.dromara.dms.doc.service.FolderService;
import org.dromara.dms.doc.service.PermissionChecker;
import org.dromara.dms.doc.service.PermissionScopeResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件夹服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FolderServiceImpl implements FolderService {

    private final DocFolderMapper folderMapper;
    private final DocFolderPermissionMapper folderPermMapper;
    private final PermissionChecker permissionChecker;
    private final DocPermissionQueryMapper permQueryMapper;
    private final PermissionScopeResolver scopeResolver;
    private final DocFileMapper fileMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocFolder create(Long parentId, String name, Long userId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("文件夹名不能为空");
        }

        DocFolder parent = parentId == 0
                ? null
                : folderMapper.selectById(parentId);
        if (parentId != 0 && parent == null) {
            throw new IllegalArgumentException("父文件夹不存在: " + parentId);
        }

        if (folderMapper.existsByName(parentId, name)) {
            throw new IllegalStateException("同名文件夹已存在: " + name);
        }

        DocFolder folder = new DocFolder()
                .setParentId(parentId)
                .setFolderName(name)
                .setFolderPath(buildPath(parent, parentId, userId))
                .setOwnerId(userId)
                .setSortOrder(0)
                .setCreateBy(userId)
                .setCreateTime(LocalDateTime.now());

        folderMapper.insert(folder);
        log.info("Folder created: id={}, name={}, parent={}", folder.getFolderId(), name, parentId);
        return folder;
    }

    @Override
    public void rename(Long folderId, String newName, Long userId) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("文件夹名不能为空");
        }
        DocFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("文件夹不存在: " + folderId);
        }
        if (folderMapper.existsByName(folder.getParentId(), newName)) {
            throw new IllegalStateException("同名文件夹已存在: " + newName);
        }
        folderMapper.rename(folderId, newName, LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(Long folderId, Long userId) {
        DocFolder folder = folderMapper.selectById(folderId);
        if (folder == null) return;

        // 收集受影响文件夹：自身 + 全部后代
        List<Long> affectedIds = new ArrayList<>();
        affectedIds.add(folderId);
        List<DocFolder> descendants = folderMapper.listDescendants(folder.getFolderPath() + folderId + "/");
        for (DocFolder d : descendants) {
            affectedIds.add(d.getFolderId());
        }

        // 软删除文件夹本身 + 后代
        folderMapper.softDelete(folderId, LocalDateTime.now());
        for (DocFolder d : descendants) {
            folderMapper.softDelete(d.getFolderId(), LocalDateTime.now());
        }

        // 软删除这些文件夹下的所有文件（保持一致，进回收站）
        for (Long fid : affectedIds) {
            List<org.dromara.dms.doc.domain.DocFile> fs = fileMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<org.dromara.dms.doc.domain.DocFile>()
                            .eq("folder_id", fid)
                            .isNull("deleted_at"));
            for (var f : fs) {
                fileMapper.softDelete(f.getFileId(), LocalDateTime.now());
            }
        }

        log.info("Folder soft deleted: id={}, folders={}, files also soft-deleted", folderId, affectedIds.size());
    }

    @Override
    public void restore(Long folderId, Long userId) {
        folderMapper.restore(folderId);
        // TODO 恢复子文件夹（按 folder_path 排序，父先恢复）和文件
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void hardDelete(Long folderId, Long userId) {
        // 先删权限
        folderPermMapper.deleteByFolder(folderId);
        // 再删文件夹
        folderMapper.hardDelete(folderId);
        log.info("Folder hard deleted: id={}", folderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void move(Long folderId, Long newParentId, Long userId) {
        DocFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("文件夹不存在: " + folderId);
        }

        DocFolder newParent = newParentId == 0
                ? null
                : folderMapper.selectById(newParentId);
        if (newParentId != 0 && newParent == null) {
            throw new IllegalArgumentException("目标父文件夹不存在: " + newParentId);
        }

        // 防环：不能把文件夹移动到自己的后代
        String newPath = buildPath(newParent, newParentId, userId);
        if (newPath.startsWith(folder.getFolderPath() + folderId + "/")) {
            throw new IllegalArgumentException("不能将文件夹移动到自身或其子目录下");
        }

        String oldPath = folder.getFolderPath();
        folderMapper.move(folderId, newParentId, newPath, LocalDateTime.now());

        // 更新所有子文件夹的 folder_path
        // pathPrefix 旧 = oldPath + folderId + "/"
        // pathPrefix 新 = newPath + folderId + "/"
        // SQL：UPDATE doc_folder SET folder_path = REPLACE(folder_path, ?, ?) WHERE folder_path LIKE ?
        // v1.0 简化：递归更新
        List<DocFolder> descendants = folderMapper.listDescendants(oldPath + folderId + "/");
        for (DocFolder d : descendants) {
            String updated = d.getFolderPath().replace(oldPath + folderId + "/", newPath + folderId + "/");
            // TODO 批量 update
        }
    }

    @Override
    public List<DocFolder> listChildren(Long parentId, Long userId) {
        List<DocFolder> children = folderMapper.listChildren(parentId);
        PermissionScopeResolver.Scope scope = scopeResolver.current();
        // 超级管理员不受权限限制
        if (scope.unrestricted()) {
            return children;
        }
        // 只保留当前用户可见（VISIBLE）的子文件夹
        List<Long> readable = permQueryMapper.selectReadableFolderIds(
                scope.userId(), scope.roleIds(), scope.deptIds());
        return children.stream()
                .filter(f -> readable.contains(f.getFolderId()))
                .toList();
    }

    @Override
    public List<DocFolder> getBreadcrumb(Long folderId) {
        if (folderId == null || folderId == 0) return Collections.emptyList();
        List<DocFolder> result = new ArrayList<>();
        Long current = folderId;
        int safety = 20;  // 防环
        while (current != null && current != 0 && safety-- > 0) {
            DocFolder f = folderMapper.selectById(current);
            if (f == null) break;
            result.add(0, f);
            current = f.getParentId();
        }
        PermissionScopeResolver.Scope scope = scopeResolver.current();
        if (scope.unrestricted()) {
            return result;
        }
        // 不可见的祖先不下发（避免泄露无权限目录名）
        List<Long> readable = permQueryMapper.selectReadableFolderIds(
                scope.userId(), scope.roleIds(), scope.deptIds());
        return result.stream()
                .filter(f -> readable.contains(f.getFolderId()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocFolder getOrCreateUserRoot(Long userId) {
        // 简化：用 folder_path = '/0/' AND owner_id = userId 查找
        // v1.0 简化版：直接 SELECT 后不存在则创建
        // 实际实现可用 LambdaQueryWrapper
        DocFolder root = folderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocFolder>()
                        .eq("owner_id", userId)
                        .eq("parent_id", 0)
                        .eq("folder_name", "我的文档")
                        .isNull("deleted_at")
                        .last("LIMIT 1")
        );

        if (root != null) return root;

        DocFolder folder = new DocFolder()
                .setParentId(0L)
                .setFolderName("我的文档")
                .setFolderPath("/0/")
                .setOwnerId(userId)
                .setSortOrder(0)
                .setCreateBy(userId)
                .setCreateTime(LocalDateTime.now());
        folderMapper.insert(folder);
        log.info("Created user root folder: user={}, folder={}", userId, folder.getFolderId());
        return folder;
    }

    /**
     * 构建新文件夹的物化路径
     */
    private String buildPath(DocFolder parent, Long parentId, Long userId) {
        if (parentId == 0 || parent == null) {
            return "/0/";
        }
        return parent.getFolderPath() + parent.getFolderId() + "/";
    }
}

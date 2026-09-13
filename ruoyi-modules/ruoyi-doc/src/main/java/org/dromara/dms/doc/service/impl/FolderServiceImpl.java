package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.mapper.DocFolderPermissionMapper;
import org.dromara.dms.doc.mapper.DocPermissionQueryMapper;
import org.dromara.dms.doc.service.DocFolderOwnerResolver;
import org.dromara.dms.doc.service.DocNameService;
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.service.FolderService;
import org.dromara.dms.doc.service.PermissionChecker;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.dms.doc.service.PermissionScopeResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    private final DocFolderOwnerResolver ownerResolver;
    private final PermissionScopeResolver scopeResolver;
    private final DocFileMapper fileMapper;
    private final FileService fileService;
    private final DocNameService nameService;
    private final PermissionService permissionService;

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
            throw new ServiceException("当前文件夹下已存在同名文件夹：「" + name + "」");
        }

        DocFolder folder = new DocFolder()
                .setParentId(parentId)
                .setFolderName(name)
                .setFolderPath(buildPath(parent, parentId, userId))
                // 所有者继承文档区所有者（完全控制归文档区所有者），创建者另记 create_by
                .setOwnerId(ownerResolver.resolveOwner(parentId, userId))
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
            throw new ServiceException("文件夹名不能为空");
        }
        String name = newName.trim();
        DocFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw new ServiceException("文件夹不存在: " + folderId);
        }
        // 名字没变则视为无改动，避免报「已存在同名文件夹」这种莫名其妙的错
        if (name.equals(folder.getFolderName())) {
            return;
        }
        if (folderMapper.existsByName(folder.getParentId(), name)) {
            throw new ServiceException("当前文件夹下已存在同名文件夹：「" + name + "」");
        }
        folderMapper.rename(folderId, name, LocalDateTime.now());
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
        if (folder == null || folder.getDeletedAt() != null) {
            throw new ServiceException("文件夹不存在或已在回收站: " + folderId);
        }

        DocFolder newParent = newParentId == 0
                ? null
                : folderMapper.selectById(newParentId);
        if (newParentId != 0 && newParent == null) {
            throw new ServiceException("目标文件夹不存在: " + newParentId);
        }

        // 防环：不能把文件夹移动到自己的后代
        String newPath = buildPath(newParent, newParentId, userId);
        String oldPrefix = folder.getFolderPath() + folderId + "/";
        if (newPath.startsWith(oldPrefix)) {
            throw new ServiceException("不能将文件夹移动到自身或其子文件夹内");
        }
        // 目标与当前所在位置相同 → 无需任何操作（避免误报同名冲突）
        if (java.util.Objects.equals(folder.getParentId(), newParentId)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        folderMapper.move(folderId, newParentId, newPath, now);

        // 同步所有后代的物化路径，否则 listDescendants 会漏、删除/复制都会出错
        int moved = folderMapper.updateDescendantPaths(oldPrefix, newPath + folderId + "/", now);
        log.info("Folder moved: id={}, {} -> {}, descendants={}", folderId, oldPrefix, newPath, moved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copy(Long folderId, Long targetParentId, boolean merge, Long userId) {
        DocFolder src = folderMapper.selectById(folderId);
        if (src == null || src.getDeletedAt() != null) {
            throw new ServiceException("源文件夹不存在或已在回收站");
        }
        Long parentId = targetParentId == null ? 0L : targetParentId;
        DocFolder target = parentId == 0 ? null : folderMapper.selectById(parentId);
        if (parentId != 0 && (target == null || target.getDeletedAt() != null)) {
            throw new ServiceException("目标文件夹不存在或已在回收站: " + parentId);
        }

        // 防环：不能复制到自己或自己的后代里（否则会无限递归）
        if (parentId != 0) {
            String srcPrefix = src.getFolderPath() + src.getFolderId() + "/";
            String targetPath = target.getFolderPath() + target.getFolderId() + "/";
            if (parentId.equals(src.getFolderId()) || targetPath.startsWith(srcPrefix)) {
                throw new ServiceException("不能将文件夹复制到自身或其子文件夹内");
            }
        }

        DocFolder dest;
        if (merge) {
            dest = folderMapper.findChildByName(parentId, src.getFolderName());
            if (dest == null) {
                throw new ServiceException("目标位置没有同名文件夹「" + src.getFolderName() + "」，无法合并");
            }
        } else {
            dest = create(parentId, nameService.uniqueFolderName(parentId, src.getFolderName()), userId);
        }
        int[] stat = new int[2];
        copyContents(src, dest, merge, userId, stat);
        log.info("Folder copied: src={} -> dest={} (merge={}), folders={}, files={}",
                folderId, dest.getFolderId(), merge, stat[0], stat[1]);
        return dest.getFolderId();
    }

    /**
     * 递归复制目录内容
     *
     * @param src    源文件夹
     * @param dest   目标文件夹（已存在）
     * @param merge  子层同名文件夹是否继续合并
     * @param stat   [0]=新建文件夹数 [1]=复制文件数
     */
    private void copyContents(DocFolder src, DocFolder dest, boolean merge, Long userId, int[] stat) {
        for (DocFile f : fileMapper.listByFolder(src.getFolderId())) {
            // 复用文件复制：同名自动重命名为「xxx (1).ext」，MinIO 对象共享不重复占空间
            fileService.copy(f.getFileId(), dest.getFolderId(), userId);
            stat[1]++;
        }
        for (DocFolder child : folderMapper.listChildren(src.getFolderId())) {
            DocFolder existed = merge
                    ? folderMapper.findChildByName(dest.getFolderId(), child.getFolderName())
                    : null;
            DocFolder childDest = existed != null
                    ? existed
                    : create(dest.getFolderId(),
                             nameService.uniqueFolderName(dest.getFolderId(), child.getFolderName()), userId);
            if (existed == null) {
                stat[0]++;
            }
            copyContents(child, childDest, merge, userId, stat);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void mergeInto(Long srcFolderId, Long destFolderId, Long userId) {
        DocFolder src = folderMapper.selectById(srcFolderId);
        DocFolder dest = folderMapper.selectById(destFolderId);
        if (src == null || src.getDeletedAt() != null) {
            throw new ServiceException("源文件夹不存在或已在回收站");
        }
        if (dest == null || dest.getDeletedAt() != null) {
            throw new ServiceException("目标文件夹不存在或已在回收站");
        }
        if (srcFolderId.equals(destFolderId)) {
            throw new ServiceException("不能把文件夹合并到它自己");
        }
        // 防环：目标不能是源的后代，否则会在移动过程中把内容移到正在遍历的子树里
        String srcPrefix = src.getFolderPath() + srcFolderId + "/";
        if ((dest.getFolderPath() + destFolderId + "/").startsWith(srcPrefix)) {
            throw new ServiceException("不能把文件夹合并到它自己的子文件夹里");
        }

        LocalDateTime now = LocalDateTime.now();
        int[] stat = new int[]{0, 0};   // [文件数, 子文件夹数]

        // 1) 文件逐个移入，同名自动重命名（不覆盖目标里的同名文件）
        for (DocFile f : fileMapper.listByFolder(srcFolderId)) {
            String name = nameService.uniqueFileName(destFolderId, f.getFileName());
            fileMapper.renameAndMove(f.getFileId(), destFolderId, name, now);
            stat[0]++;
        }

        // 2) 子文件夹：同名递归合并，否则整体搬过去（move 会同步其下所有物化路径）
        for (DocFolder child : folderMapper.listChildren(srcFolderId)) {
            DocFolder same = folderMapper.findChildByName(destFolderId, child.getFolderName());
            if (same != null) {
                mergeInto(child.getFolderId(), same.getFolderId(), userId);
            } else {
                move(child.getFolderId(), destFolderId, userId);
            }
            stat[1]++;
        }

        // 3) 源文件夹此时已空：内容都还在目标里，直接物理删除，不留空壳也不占回收站
        folderPermMapper.deleteByFolder(srcFolderId);
        folderMapper.hardDelete(srcFolderId);
        log.info("Folder merged: src={} -> dest={}, files={}, subfolders={}",
                srcFolderId, destFolderId, stat[0], stat[1]);
    }

    @Override
    public boolean hasChildNamed(Long parentId, String name) {
        return parentId != null && name != null && !name.isBlank()
                && folderMapper.existsByName(parentId, name);
    }

    @Override
    public List<DocFolder> listChildren(Long parentId, Long userId) {
        List<DocFolder> children = folderMapper.listChildren(parentId);
        PermissionScopeResolver.Scope scope = scopeResolver.current();
        List<DocFolder> visible;
        if (scope.unrestricted()) {
            // 超级管理员不受权限限制
            visible = children;
        } else {
            // 只保留当前用户可见（VISIBLE）的子文件夹
            List<Long> readable = permQueryMapper.selectReadableFolderIds(
                    scope.userId(), scope.roleIds(), scope.deptIds());
            visible = children.stream()
                    .filter(f -> readable.contains(f.getFolderId()))
                    .toList();
        }
        // 带上当前用户对每个文件夹的权限位：前端据此决定「下载文件夹(zip)」等入口是否可用。
        // 缺了这一步，文件夹的 userFlags 是 undefined，打包下载入口会永久置灰。
        List<DocFolder> result = new ArrayList<>(visible);
        fillUserFlags(result, userId);
        return result;
    }

    /** 给文件夹列表填充当前用户的权限位 */
    private void fillUserFlags(List<DocFolder> folders, Long userId) {
        if (folders == null || folders.isEmpty()) {
            return;
        }
        Long uid = userId != null ? userId : LoginHelper.getUserId();
        Map<Long, Integer> flags = permissionService.computeFolderFlagsBatch(folders, uid);
        folders.forEach(f -> f.setUserFlags(flags.getOrDefault(f.getFolderId(), 0)));
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

package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.dto.CreateFolderRequest;
import org.dromara.dms.doc.dto.MoveFolderRequest;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.FolderService;
import org.dromara.dms.doc.service.PermissionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 文件夹 REST API
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;
    private final PermissionService permissionService;


    /** 列子文件夹 */
    @GetMapping("/children")
    public R<List<DocFolder>> listChildren(@RequestParam Long parentId) {
        Long userId = LoginHelper.getUserId();
        return R.ok(folderService.listChildren(parentId, userId));
    }

    /**
     * 创建文件夹
     *
     * <p>顶层（parentId=0）即公司文档区，<b>仅超级管理员可创建</b>；
     * 文档区内建子目录需父文件夹「创建子项」权限。
     */
    @PostMapping
    public R<DocFolder> create(@RequestBody CreateFolderRequest req) {
        Long userId = LoginHelper.getUserId();
        Long parentId = req.getParentId();
        if (parentId == null || parentId == 0L) {
            if (!LoginHelper.isSuperAdmin()) {
                throw new ServiceException("仅超级管理员可创建顶层文档区");
            }
        } else {
            // 「创建子项」位已取消：在目录内新建子目录归入「上传」语义
            permissionService.requireFolder(parentId, PermissionFlag.UPLOAD, userId);
        }
        return R.ok(folderService.create(parentId, req.getName(), userId));
    }

    /** 重命名（需「编辑」权限） */
    @PutMapping("/{folderId}/rename")
    public R<Void> rename(@PathVariable Long folderId, @RequestParam String name) {
        Long userId = LoginHelper.getUserId();
        // 「编辑」位已取消：重命名/移动需完全控制
        permissionService.requireFolder(folderId, PermissionFlag.FULL_CONTROL, userId);
        folderService.rename(folderId, name, userId);
        return R.ok();
    }

    /** 移动（源需「完全控制」，目标需「上传」；移动到顶层仅超级管理员） */
    @PutMapping("/{folderId}/move")
    public R<Void> move(@PathVariable Long folderId, @RequestBody MoveFolderRequest req) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.FULL_CONTROL, userId);
        Long newParentId = req.getNewParentId();
        if (newParentId == null || newParentId == 0L) {
            if (!LoginHelper.isSuperAdmin()) {
                throw new ServiceException("仅超级管理员可调整顶层文档区");
            }
        } else {
            // 目标父目录需「上传」权限（往目录里添东西）
            permissionService.requireFolder(newParentId, PermissionFlag.UPLOAD, userId);
        }
        folderService.move(folderId, newParentId, userId);
        return R.ok();
    }

    /** 软删除（到回收站，需「删除」权限；级联删除其下文件） */
    @DeleteMapping("/{folderId}")
    public R<Void> softDelete(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.DELETE, userId);
        folderService.softDelete(folderId, userId);
        return R.ok();
    }

    /** 面包屑 */
    @GetMapping("/{folderId}/breadcrumb")
    public R<List<DocFolder>> breadcrumb(@PathVariable Long folderId) {
        return R.ok(folderService.getBreadcrumb(folderId));
    }
}

package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.dto.CopyFolderRequest;
import org.dromara.dms.doc.dto.CreateFolderRequest;
import org.dromara.dms.doc.dto.MergeFolderRequest;
import org.dromara.dms.doc.dto.MoveFolderRequest;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.service.AuditService;
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
    private final AuditService auditService;
    private final DocFolderMapper folderMapper;


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
        DocFolder created = folderService.create(parentId, req.getName(), userId);
        auditService.record(AuditAction.CREATE_FOLDER, "FOLDER", created.getFolderId(),
                created.getFolderName(), java.util.Map.of(
                        "parentId", String.valueOf(parentId),
                        "folderPath", created.getFolderPath() == null ? "" : created.getFolderPath()));
        return R.ok(created);
    }

    /** 重命名（需可管理：读写档即含「编辑」位） */
    @PutMapping("/{folderId}/rename")
    public R<Void> rename(@PathVariable Long folderId, @RequestParam String name) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolderManageable(folderId, userId);
        DocFolder before = folderMapper.selectById(folderId);
        folderService.rename(folderId, name, userId);
        auditService.record(AuditAction.RENAME, "FOLDER", folderId, name,
                java.util.Map.of("oldName", before.getFolderName() == null ? "" : before.getFolderName()));
        return R.ok();
    }

    /** 移动（源需「完全控制」，目标需「上传」；移动到顶层仅超级管理员） */
    @PutMapping("/{folderId}/move")
    public R<Void> move(@PathVariable Long folderId, @RequestBody MoveFolderRequest req) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolderManageable(folderId, userId);
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
        auditService.record(AuditAction.MOVE, "FOLDER", folderId, null,
                java.util.Map.of("newParentId", String.valueOf(newParentId)));
        return R.ok();
    }

    /**
     * 复制文件夹（连同子目录与文件）
     *
     * <p>权限：源文件夹需「可见」（能读才能复制），目标文件夹需「上传」（往里面添东西）。
     */
    @PostMapping("/{folderId}/copy")
    public R<Long> copy(@PathVariable Long folderId, @RequestBody CopyFolderRequest req) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.VISIBLE, userId);
        Long targetParentId = req.getTargetParentId() == null ? 0L : req.getTargetParentId();
        if (targetParentId == 0L) {
            throw new ServiceException("请选择目标文件夹");
        }
        permissionService.requireFolder(targetParentId, PermissionFlag.UPLOAD, userId);
        Long newId = folderService.copy(folderId, targetParentId,
                Boolean.TRUE.equals(req.getMerge()), userId);
        auditService.record(AuditAction.CREATE_FOLDER, "FOLDER", newId, null,
                java.util.Map.of("copiedFrom", String.valueOf(folderId),
                        "merge", String.valueOf(Boolean.TRUE.equals(req.getMerge()))));
        return R.ok(newId);
    }

    /**
     * 把文件夹合并进另一个已存在的文件夹（剪切粘贴遇到同名文件夹时选「合并内容」）
     *
     * <p>权限：源文件夹需「删除」（合并会销毁源目录本身），目标文件夹需「上传」。
     */
    @PostMapping("/{folderId}/merge-into")
    public R<Void> mergeInto(@PathVariable Long folderId, @RequestBody MergeFolderRequest req) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.DELETE, userId);
        if (req.getDestFolderId() == null) {
            throw new ServiceException("请选择要合并到的文件夹");
        }
        permissionService.requireFolder(req.getDestFolderId(), PermissionFlag.UPLOAD, userId);
        folderService.mergeInto(folderId, req.getDestFolderId(), userId);
        auditService.record(AuditAction.MOVE, "FOLDER", folderId, null,
                java.util.Map.of("mergedInto", String.valueOf(req.getDestFolderId())));
        return R.ok();
    }

    /** 目标位置是否已有同名子文件夹（前端据此弹「合并内容」提示） */
    @GetMapping("/{folderId}/has-child")
    public R<Boolean> hasChild(@PathVariable Long folderId, @RequestParam String name) {
        return R.ok(folderService.hasChildNamed(folderId, name));
    }

    /** 软删除（到回收站，需「删除」权限；级联删除其下文件） */
    @DeleteMapping("/{folderId}")
    public R<Void> softDelete(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.DELETE, userId);
        folderService.softDelete(folderId, userId);
        auditService.record(AuditAction.DELETE, "FOLDER", folderId, null);
        return R.ok();
    }

    /** 面包屑 */
    @GetMapping("/{folderId}/breadcrumb")
    public R<List<DocFolder>> breadcrumb(@PathVariable Long folderId) {
        return R.ok(folderService.getBreadcrumb(folderId));
    }
}

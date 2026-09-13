package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.service.FolderService;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.service.AuditService;
import org.dromara.dms.doc.service.RecycleService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回收站 REST API
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/recycle")
@RequiredArgsConstructor
public class RecycleController {

    private final DocFileMapper fileMapper;
    private final DocFolderMapper folderMapper;
    private final FileService fileService;
    private final FolderService folderService;
    private final RecycleService recycleService;
    private final AuditService auditService;

    /**
     * 回收站列表：返回 {folders: [...], files: [...], retentionDays: n}
     *
     * <p>retentionDays 供界面提示「超过 N 天自动清理」，与定时任务用的是同一个配置项。
     */
    @GetMapping("/list")
    public R<Map<String, Object>> list() {
        Long userId = LoginHelper.getUserId();
        Map<String, Object> result = new HashMap<>();
        // 与定时任务用同一份解析逻辑，避免界面显示与实际清理策略不一致
        result.put("retentionDays", recycleService.resolveRetentionDays());
        // 普通用户只能看到自己（上传/所有）的回收站内容，超级管理员可见全部
        if (LoginHelper.isSuperAdmin()) {
            result.put("folders", folderMapper.listDeletedFolders());
            result.put("files", fileMapper.listDeleted());
        } else {
            result.put("folders", folderMapper.listDeletedFoldersByOwner(userId));
            result.put("files", fileMapper.listDeletedByCreator(userId));
        }
        return R.ok(result);
    }

    /** 校验当前用户可操作该回收站文件（上传者本人或超级管理员） */
    private void assertFileOwner(DocFile file, Long userId) {
        if (file == null) return;
        if (LoginHelper.isSuperAdmin()) return;
        if (!userId.equals(file.getCreatorId())) {
            throw new ServiceException("无权操作他人文件");
        }
    }

    /** 校验当前用户可操作该回收站文件夹（所有者本人或超级管理员） */
    private void assertFolderOwner(DocFolder folder, Long userId) {
        if (folder == null) return;
        if (LoginHelper.isSuperAdmin()) return;
        if (!userId.equals(folder.getOwnerId())) {
            throw new ServiceException("无权操作他人文件夹");
        }
    }

    /**
     * 恢复文件
     */
    @PostMapping("/files/{fileId}/restore")
    public R<Void> restoreFile(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        assertFileOwner(fileMapper.selectById(fileId), userId);
        fileService.restore(fileId, userId);
        auditService.record(AuditAction.RESTORE, "FILE", fileId, null);
        return R.ok();
    }

    /**
     * 恢复文件夹（及其下直接子项——含已删文件所在文件夹仍在）
     */
    @PostMapping("/folders/{folderId}/restore")
    public R<Void> restoreFolder(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        assertFolderOwner(folderMapper.selectById(folderId), userId);
        folderService.restore(folderId, userId);
        auditService.record(AuditAction.RESTORE, "FOLDER", folderId, null);
        return R.ok();
    }

    /**
     * 永久删除文件
     *
     * <p>走 RecycleService：记录、全文索引与 MinIO 物理对象一起清理
     * （对象仍被其它记录引用时会保留，避免误删秒传/复制出来的同源文件）。
     */
    @DeleteMapping("/files/{fileId}")
    public R<Void> purgeFile(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        DocFile file = fileMapper.selectById(fileId);
        assertFileOwner(file, userId);
        int objects = recycleService.purgeFile(fileId);
        log.info("文件已永久删除: id={}, 清理物理对象={}", fileId, objects);
        auditService.record(AuditAction.PERMANENT_DELETE, "FILE", fileId,
                file == null ? null : file.getFileName(),
                java.util.Map.of("removedObjects", objects));
        return R.ok();
    }

    /**
     * 永久删除文件夹（物理删除整棵子树）
     */
    @DeleteMapping("/folders/{folderId}")
    public R<Void> purgeFolder(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        DocFolder folder = folderMapper.selectById(folderId);
        assertFolderOwner(folder, userId);
        int objects = recycleService.purgeFolder(folderId);
        log.info("文件夹已永久删除: id={}, 清理物理对象={}", folderId, objects);
        auditService.record(AuditAction.PERMANENT_DELETE, "FOLDER", folderId,
                folder == null ? null : folder.getFolderName(),
                java.util.Map.of("removedObjects", objects));
        return R.ok();
    }

    /**
     * 清空回收站
     *
     * <p>普通用户只清空自己的，超级管理员清空全部。
     */
    @DeleteMapping("/empty")
    public R<Void> empty() {
        Long userId = LoginHelper.getUserId();
        boolean superAdmin = LoginHelper.isSuperAdmin();
        RecycleService.CleanupResult result = recycleService.emptyRecycleBin(userId, !superAdmin);
        log.info("回收站已清空: {}", result);
        auditService.record(AuditAction.PERMANENT_DELETE, "RECYCLE_BIN", null, null,
                java.util.Map.of("folders", result.folders(), "files", result.files(),
                        "objects", result.objects()));
        return R.ok();
    }
}

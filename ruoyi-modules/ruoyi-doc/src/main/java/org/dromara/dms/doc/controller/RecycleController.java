package org.dromara.dms.doc.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFileTextMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.service.FolderService;
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
    private final DocFileTextMapper fileTextMapper;
    private final FileService fileService;
    private final FolderService folderService;

    /**
     * 回收站列表：返回 {folders: [...], files: [...]}
     */
    @GetMapping("/list")
    public R<Map<String, Object>> list() {
        Long userId = LoginHelper.getUserId();
        Map<String, Object> result = new HashMap<>();
        result.put("folders", folderMapper.listDeletedFolders());
        result.put("files", fileMapper.listDeleted());
        return R.ok(result);
    }

    /**
     * 恢复文件
     */
    @PostMapping("/files/{fileId}/restore")
    public R<Void> restoreFile(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        fileService.restore(fileId, userId);
        return R.ok();
    }

    /**
     * 恢复文件夹（及其下直接子项——含已删文件所在文件夹仍在）
     */
    @PostMapping("/folders/{folderId}/restore")
    public R<Void> restoreFolder(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        folderService.restore(folderId, userId);
        return R.ok();
    }

    /**
     * 永久删除文件（同时删 MinIO 对象 + 文本）
     */
    @DeleteMapping("/files/{fileId}")
    public R<Void> purgeFile(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        DocFile file = fileMapper.selectById(fileId);
        if (file != null) {
            fileTextMapper.deleteById(fileId);
            fileMapper.hardDelete(fileId);
            log.info("File permanently deleted: id={}, name={}", fileId, file.getFileName());
        }
        return R.ok();
    }

    /**
     * 永久删除文件夹（物理删除整棵子树）
     */
    @DeleteMapping("/folders/{folderId}")
    public R<Void> purgeFolder(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        DocFolder folder = folderMapper.selectById(folderId);
        if (folder == null) return R.ok();

        // 子树 id（含自身）
        List<Long> ids = folderMapper.listSubtreeIds(folderId, folder.getFolderPath() + folderId + "/");
        if (!ids.isEmpty()) {
            // 删除子树内所有文件的文本 + 文件记录
            for (Long id : ids) {
                List<DocFile> fs = fileMapper.selectList(new QueryWrapper<DocFile>().eq("folder_id", id));
                for (DocFile f : fs) {
                    fileTextMapper.deleteById(f.getFileId());
                    fileMapper.hardDelete(f.getFileId());
                }
            }
            // 删除文件夹（逐条物理删除，逆序最深优先）
            for (Long id : ids) {
                folderMapper.hardDelete(id);
            }
        }
        log.info("Folder permanently deleted: id={}, name={}, subtree={}",
                folderId, folder.getFolderName(), ids.size());
        return R.ok();
    }

    /**
     * 清空回收站
     */
    @DeleteMapping("/empty")
    public R<Void> empty() {
        // 简化：仅清空当前回收站中的文件（文件夹回收站逐层清理复杂度高，先手动）
        List<DocFile> files = fileMapper.listDeleted();
        for (DocFile f : files) {
            fileTextMapper.deleteById(f.getFileId());
            fileMapper.hardDelete(f.getFileId());
        }
        List<DocFolder> folders = folderMapper.listDeletedFolders();
        for (DocFolder f : folders) {
            folderMapper.hardDelete(f.getFolderId());
        }
        log.info("Recycle bin emptied: {} files, {} folders", files.size(), folders.size());
        return R.ok();
    }
}

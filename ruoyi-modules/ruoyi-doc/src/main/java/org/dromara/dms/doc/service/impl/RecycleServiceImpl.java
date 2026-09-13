package org.dromara.dms.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFileTextMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.mapper.DocFolderPermissionMapper;
import org.dromara.dms.doc.service.RecycleService;
import org.dromara.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收站服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecycleServiceImpl implements RecycleService {

    /** 物理对象所在列名（由调用方以常量传入，避免 SQL 注入） */
    private static final String COL_STORAGE = "storage_key";
    private static final String COL_PREVIEW = "preview_key";
    private static final String COL_THUMBNAIL = "thumbnail_key";

    private final DocFileMapper fileMapper;
    private final DocFolderMapper folderMapper;
    private final DocFileTextMapper fileTextMapper;
    private final DocFolderPermissionMapper folderPermMapper;
    private final MinioClient minioClient;
    private final ISysConfigService configService;

    /** 系统参数中的保留天数字段（与 RuoYi「系统参数」页面共用同一份配置） */
    private static final String CFG_RETENTION_DAYS = "sys.recycle.retentionDays";

    /** 系统参数未配置时的兜底保留天数 */
    @Value("${dms.recycle.retention-days:7}")
    private int fallbackRetentionDays;

    @Override
    public int resolveRetentionDays() {
        try {
            String value = configService.selectConfigByKey(CFG_RETENTION_DAYS);
            if (value != null && !value.isBlank()) {
                return Integer.parseInt(value.trim());
            }
        } catch (NumberFormatException e) {
            log.warn("系统参数 {} 不是合法数字，回落到配置项 {}",
                    CFG_RETENTION_DAYS, fallbackRetentionDays);
        } catch (Exception e) {
            log.warn("读取系统参数 {} 失败，回落到配置项 {}", CFG_RETENTION_DAYS, fallbackRetentionDays, e);
        }
        return fallbackRetentionDays;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeFile(Long fileId) {
        DocFile file = fileMapper.selectById(fileId);
        if (file == null) {
            return 0;
        }
        return purgeFileInternal(file);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeFolder(Long folderId) {
        DocFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            return 0;
        }
        return purgeFolderInternal(folder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CleanupResult emptyRecycleBin(Long userId, boolean onlyOwn) {
        int folders = 0;
        int files = 0;
        int objects = 0;

        // 1) 文件夹：先清（会把子树里的文件一起带走），再处理游离在回收站的文件
        List<DocFolder> deletedFolders = onlyOwn
                ? folderMapper.listDeletedFoldersByOwner(userId)
                : folderMapper.listDeletedFolders();
        for (DocFolder f : deletedFolders) {
            if (folderMapper.selectById(f.getFolderId()) == null) {
                continue;   // 已被父级子树带走
            }
            objects += purgeFolderInternal(f);
            folders++;
        }

        // 2) 文件
        List<DocFile> deletedFiles = onlyOwn
                ? fileMapper.listDeletedByCreator(userId)
                : fileMapper.listDeleted();
        for (DocFile f : deletedFiles) {
            if (fileMapper.selectById(f.getFileId()) == null) {
                continue;   // 已随文件夹一起删掉
            }
            objects += purgeFileInternal(f);
            files++;
        }

        log.info("清空回收站完成（onlyOwn={}, user={}）: {}", onlyOwn, userId,
                new CleanupResult(folders, files, objects));
        return new CleanupResult(folders, files, objects);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CleanupResult cleanupExpired(int retentionDays) {
        if (retentionDays <= 0) {
            log.info("回收站自动清理已关闭（retention-days={}）", retentionDays);
            return new CleanupResult(0, 0, 0);
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int folders = 0;
        int files = 0;
        int objects = 0;

        // 文件夹按 folder_path 升序返回，父目录在前；先删父即可连带整个子树，
        // 后面的子目录分支会因为记录已不存在而直接跳过，不会重复删。
        for (DocFolder f : folderMapper.listExpiredDeletedFolders(cutoff)) {
            if (folderMapper.selectById(f.getFolderId()) == null) {
                continue;
            }
            objects += purgeFolderInternal(f);
            folders++;
        }

        for (DocFile f : fileMapper.listExpiredDeleted(cutoff)) {
            if (fileMapper.selectById(f.getFileId()) == null) {
                continue;
            }
            objects += purgeFileInternal(f);
            files++;
        }

        CleanupResult result = new CleanupResult(folders, files, objects);
        if (!result.isEmpty()) {
            log.info("回收站自动清理完成（保留 {} 天，截止 {}）: {}", retentionDays, cutoff, result);
        }
        return result;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /** 删一条文件记录：文本索引 + 记录 + 判定后可删的物理对象 */
    private int purgeFileInternal(DocFile file) {
        fileTextMapper.deleteById(file.getFileId());
        fileMapper.hardDelete(file.getFileId());
        int removed = 0;
        removed += removeObjectIfOrphan(file.getStorageBucket(), file.getStorageKey(), COL_STORAGE);
        removed += removeObjectIfOrphan(file.getStorageBucket(), file.getPreviewKey(), COL_PREVIEW);
        removed += removeObjectIfOrphan(file.getStorageBucket(), file.getThumbnailKey(), COL_THUMBNAIL);
        log.debug("文件已永久删除: id={}, name={}, 清理物理对象={}",
                file.getFileId(), file.getFileName(), removed);
        return removed;
    }

    /** 删一棵文件夹子树（先文件后文件夹，由深到浅） */
    private int purgeFolderInternal(DocFolder folder) {
        Long folderId = folder.getFolderId();
        List<Long> ids = folderMapper.listSubtreeIds(folderId, folder.getFolderPath() + folderId + "/");
        int objects = 0;

        // 1) 子树内所有文件夹下的文件（含已软删除的，它们同属这棵树）
        for (Long id : ids) {
            List<DocFile> files = fileMapper.selectList(
                    new QueryWrapper<DocFile>().eq("folder_id", id));
            for (DocFile f : files) {
                objects += purgeFileInternal(f);
            }
        }

        // 2) 文件夹本身及其子树：先删权限，再由深到浅物理删除
        for (Long id : ids) {
            folderPermMapper.deleteByFolder(id);
        }
        ids.sort(java.util.Comparator.reverseOrder());
        for (Long id : ids) {
            folderMapper.hardDelete(id);
        }

        log.debug("文件夹已永久删除: id={}, name={}, 子树={}, 清理物理对象={}",
                folderId, folder.getFolderName(), ids.size(), objects);
        return objects;
    }

    /**
     * 若没有别的记录再引用该对象，则从 MinIO 删除
     *
     * <p>计数包含回收站中的记录：它们随时可能被恢复，对象不能提前删掉。
     *
     * @return 1 表示删除了物理对象，0 表示跳过
     */
    private int removeObjectIfOrphan(String bucket, String key, String column) {
        if (bucket == null || key == null || key.isBlank()) {
            return 0;
        }
        int refs = fileMapper.countByObjectKey(column, key);
        if (refs > 0) {
            log.debug("对象仍被 {} 条记录引用，保留: {}", refs, key);
            return 0;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            return 1;
        } catch (Exception e) {
            // 对象删不掉不应影响数据库层面的删除结果，记警告人工排查
            log.warn("删除 MinIO 对象失败: bucket={}, key={}", bucket, key, e);
            return 0;
        }
    }
}

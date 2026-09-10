package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFilePermissionMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.mapper.DocFolderPermissionMapper;
import org.dromara.dms.doc.service.PermissionChecker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 权限检查器实现
 *
 * <p>合并策略（取并集）：
 * <ol>
 *   <li>文件直接权限（doc_file_permission）</li>
 *   <li>文件所在文件夹权限（doc_folder_permission，递归向上）</li>
 *   <li>用户所属角色权限</li>
 *   <li>用户所属部门权限（含父部门链）</li>
 * </ol>
 *
 * <p>Redis 缓存：key = "dms:perm:{userId}:file:{fileId}"，value = 位掩码字符串
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionCheckerImpl implements PermissionChecker {

    private final DocFilePermissionMapper filePermMapper;
    private final DocFolderPermissionMapper folderPermMapper;
    private final DocFileMapper fileMapper;
    private final DocFolderMapper folderMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_KEY_PREFIX = "dms:perm:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    @Override
    public boolean hasFilePermission(Long userId, Set<Long> roleIds, Set<Long> deptIds,
                                     Long fileId, PermissionFlag requiredFlag) {
        int flags = computeFileFlags(userId, roleIds, deptIds, fileId);
        return PermissionFlag.has(flags, requiredFlag);
    }

    @Override
    public boolean hasFolderPermission(Long userId, Set<Long> roleIds, Set<Long> deptIds,
                                       Long folderId, PermissionFlag requiredFlag) {
        int flags = computeFolderFlags(userId, roleIds, deptIds, folderId);
        return PermissionFlag.has(flags, requiredFlag);
    }

    @Override
    public int computeFileFlags(Long userId, Set<Long> roleIds, Set<Long> deptIds, Long fileId) {
        // 1. 先查缓存
        String cacheKey = CACHE_KEY_PREFIX + userId + ":file:" + fileId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return Integer.parseInt(cached);
        }

        // 2. 所有者（上传者）自动完全控制 —— 与 PermissionServiceImpl 口径一致
        DocFile file = fileMapper.selectById(fileId);
        if (file != null && userId.equals(file.getCreatorId())) {
            return PermissionFlag.FULL;
        }

        // 3. 文件直接权限
        int flags = filePermMapper.sumFlags(fileId, userId, roleIds, deptIds);

        // 4. 若文件无直接权限或权限位不全，沿父文件夹链继承
        Long folderId = fileMapper.getFolderId(fileId);
        if (folderId != null) {
            flags |= computeFolderFlagsWithCache(userId, roleIds, deptIds, folderId, new HashSet<>());
        }

        // 4. 写缓存
        try {
            redisTemplate.opsForValue().set(cacheKey, String.valueOf(flags), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache permission for user={}, file={}", userId, fileId, e);
        }

        return flags;
    }

    @Override
    public int computeFolderFlags(Long userId, Set<Long> roleIds, Set<Long> deptIds, Long folderId) {
        return computeFolderFlagsWithCache(userId, roleIds, deptIds, folderId, new HashSet<>());
    }

    /**
     * 递归计算文件夹权限（含父文件夹继承）
     *
     * @param visited 防环（罕见但要防：parent_id 自环）
     */
    private int computeFolderFlagsWithCache(Long userId, Set<Long> roleIds, Set<Long> deptIds,
                                            Long folderId, Set<Long> visited) {
        if (folderId == null || folderId == 0L || !visited.add(folderId)) {
            return 0;
        }

        // 0. 所有者自动完全控制 —— 与 PermissionServiceImpl 口径一致
        DocFolder owned = folderMapper.selectById(folderId);
        if (owned != null && userId.equals(owned.getOwnerId())) {
            return PermissionFlag.FULL;
        }

        // 1. 当前文件夹权限
        int flags = folderPermMapper.sumFlags(folderId, userId, roleIds, deptIds);

        // 2. 递归到父文件夹
        Long parentId = folderMapper.getParentId(folderId);
        if (parentId != null && parentId != 0L) {
            flags |= computeFolderFlagsWithCache(userId, roleIds, deptIds, parentId, visited);
        }

        return flags;
    }

    @Override
    public void evictUserCache(Long userId) {
        try {
            // 简单粗暴：用 SCAN 找所有 dms:perm:{userId}:* 删除
            String pattern = CACHE_KEY_PREFIX + userId + ":*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Failed to evict permission cache for user={}", userId, e);
        }
    }
}

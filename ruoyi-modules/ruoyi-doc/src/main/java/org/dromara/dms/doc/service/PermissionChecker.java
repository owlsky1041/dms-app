package org.dromara.dms.doc.service;

import org.dromara.dms.doc.enums.PermissionFlag;

import java.util.Set;

/**
 * 权限检查器接口
 *
 * <p>实现需要考虑：
 * <ul>
 *   <li>文件直接权限（最高优先级）</li>
 *   <li>父文件夹继承权限（递归向上）</li>
 *   <li>用户所属角色权限</li>
 *   <li>用户所属部门权限</li>
 *   <li>Redis 缓存（TTL 默认 5 分钟）</li>
 * </ul>
 *
 * <p>合并策略：取并集，权限位任一为 1 即认为有该权限。
 *
 * @author DMS
 */
public interface PermissionChecker {

    /**
     * 检查当前用户对文件是否有指定权限
     *
     * @param userId       用户 ID
     * @param roleIds      用户所有角色 ID
     * @param deptIds      用户所有部门 ID（含父部门链）
     * @param fileId       文件 ID
     * @param requiredFlag 需要的权限位
     * @return true=有权限
     */
    boolean hasFilePermission(Long userId, Set<Long> roleIds, Set<Long> deptIds,
                              Long fileId, PermissionFlag requiredFlag);

    /**
     * 检查当前用户对文件夹是否有指定权限（含子项继承）
     */
    boolean hasFolderPermission(Long userId, Set<Long> roleIds, Set<Long> deptIds,
                                Long folderId, PermissionFlag requiredFlag);

    /**
     * 计算用户对文件的有效权限位（合并后）
     */
    int computeFileFlags(Long userId, Set<Long> roleIds, Set<Long> deptIds, Long fileId);

    /**
     * 计算用户对文件夹的有效权限位
     */
    int computeFolderFlags(Long userId, Set<Long> roleIds, Set<Long> deptIds, Long folderId);

    /**
     * 清除用户权限缓存（授权变更后调用）
     */
    void evictUserCache(Long userId);
}

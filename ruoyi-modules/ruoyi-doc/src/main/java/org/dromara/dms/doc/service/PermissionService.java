package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolderPermission;
import org.dromara.dms.doc.dto.GrantPermissionRequest;
import org.dromara.dms.doc.enums.PermissionFlag;

import java.util.Collection;
import java.util.List;

/**
 * 权限服务接口
 *
 * @author DMS
 */
public interface PermissionService {

    /**
     * 列出文件夹权限
     */
    List<DocFolderPermission> listFolderPermissions(Long folderId, Long operatorId);

    /**
     * 列出文件权限
     */
    List<DocFilePermission> listFilePermissions(Long fileId, Long operatorId);

    /**
     * 授权（folder/file + user/role/dept）
     */
    void grant(GrantPermissionRequest req, Long operatorId);

    /**
     * 撤销
     */
    void revoke(String resourceType, Long resourceId, String subjectType,
                Long subjectId, Long operatorId);

    /**
     * 计算当前用户对资源的有效权限位（含继承、角色、部门合并）
     */
    int computeUserFlags(String resourceType, Long resourceId, Long userId);

    // ================= 统一校验入口 =================
    // 说明：超级管理员与资源所有者（folder.owner_id / file.creator_id）自动通过。
    // 所有写操作与读操作都必须经过这里，避免出现「列表过滤了但可直接按 id 访问」的不对称。

    /**
     * 校验用户是否具备文件的指定权限位，不具备抛 ServiceException
     */
    void requireFile(Long fileId, PermissionFlag flag, Long userId);

    /**
     * 校验用户是否具备文件夹的指定权限位，不具备抛 ServiceException
     *
     * <p>folderId 为 null 或 0（根层级）时无需校验（新建资源归属创建者本人）。
     */
    void requireFolder(Long folderId, PermissionFlag flag, Long userId);

    /**
     * 批量校验文件夹权限（任一不满足即抛异常）
     */
    void requireFolders(Collection<Long> folderIds, PermissionFlag flag, Long userId);

    /**
     * 是否具备文件权限（不抛异常）
     */
    boolean hasFile(Long fileId, PermissionFlag flag, Long userId);

    /**
     * 是否具备文件夹权限（不抛异常）
     */
    boolean hasFolder(Long folderId, PermissionFlag flag, Long userId);
}

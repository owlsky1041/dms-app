package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolderPermission;
import org.dromara.dms.doc.dto.GrantPermissionRequest;

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
}

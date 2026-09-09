package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolderPermission;
import org.dromara.dms.doc.dto.GrantPermissionRequest;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.PermissionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 权限分配 REST API
 *
 * <p>文档需求：三主体（user/role/dept）× 八权限位，权限继承。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/perm")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * 查看文件夹已授权列表
     */
    @GetMapping("/folders/{folderId}")
    public R<List<DocFolderPermission>> listFolderPerms(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        return R.ok(permissionService.listFolderPermissions(folderId, userId));
    }

    /**
     * 文件夹授权
     */
    @PostMapping("/folders/{folderId}/grant")
    public R<Void> grantFolder(@PathVariable Long folderId, @RequestBody GrantPermissionRequest req) {
        Long userId = LoginHelper.getUserId();
        req.setResourceType("folder");
        req.setResourceId(folderId);
        permissionService.grant(req, userId);
        return R.ok();
    }

    /**
     * 撤销文件夹权限
     */
    @DeleteMapping("/folders/{folderId}/revoke")
    public R<Void> revokeFolder(@PathVariable Long folderId,
                                @RequestParam String subjectType,
                                @RequestParam Long subjectId) {
        Long userId = LoginHelper.getUserId();
        permissionService.revoke("folder", folderId, subjectType, subjectId, userId);
        return R.ok();
    }

    /**
     * 查看文件已授权列表
     */
    @GetMapping("/files/{fileId}")
    public R<List<DocFilePermission>> listFilePerms(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        return R.ok(permissionService.listFilePermissions(fileId, userId));
    }

    /**
     * 文件授权
     */
    @PostMapping("/files/{fileId}/grant")
    public R<Void> grantFile(@PathVariable Long fileId, @RequestBody GrantPermissionRequest req) {
        Long userId = LoginHelper.getUserId();
        req.setResourceType("file");
        req.setResourceId(fileId);
        permissionService.grant(req, userId);
        return R.ok();
    }

    /**
     * 撤销文件权限
     */
    @DeleteMapping("/files/{fileId}/revoke")
    public R<Void> revokeFile(@PathVariable Long fileId,
                              @RequestParam String subjectType,
                              @RequestParam Long subjectId) {
        Long userId = LoginHelper.getUserId();
        permissionService.revoke("file", fileId, subjectType, subjectId, userId);
        return R.ok();
    }

    /**
     * 检查当前用户对资源的有效权限位
     */
    @GetMapping("/check")
    public R<Integer> check(@RequestParam String resourceType,
                            @RequestParam Long resourceId) {
        Long userId = LoginHelper.getUserId();
        int flags = permissionService.computeUserFlags(resourceType, resourceId, userId);
        return R.ok(flags);
    }

    /**
     * 权限位常量（前端参考）
     */
    @GetMapping("/flags")
    public R<List<PermissionFlag>> flags() {
        return R.ok(List.of(PermissionFlag.values()));
    }
}

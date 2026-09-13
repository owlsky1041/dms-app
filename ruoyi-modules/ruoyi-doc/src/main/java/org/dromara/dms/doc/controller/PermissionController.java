package org.dromara.dms.doc.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolderPermission;
import org.dromara.dms.doc.dto.GrantPermissionRequest;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.service.AuditService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysUser;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 权限分配 REST API
 *
 * <p>文档需求：三主体（user/role/dept）× 八权限位，权限继承。
 *
 * <p><b>两道闸门</b>，缺一不可：
 * <ol>
 *   <li>能力闸门 —— 注解 {@code @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)}。
 *       权限串由内置超管通过「角色管理 → 分配权限」勾给某个角色，
 *       对应菜单树里「全部文档」下的两个按钮：「权限分配」{@code doc:perm:grant}
 *       与「完全控制」{@code doc:perm:full}（建库时就有的两条，本轮才真正接上代码）。
 *       没有它的人连接口都进不来。</li>
 *   <li>范围闸门 —— 目标资源上的「完全控制」位（128）。
 *       能配权限 ≠ 能配任何地方：只在被授予完全控制的那棵目录/那个文件上有授权入口。</li>
 * </ol>
 *
 * <p>为什么仍要范围闸门：能力闸门是全局的，若只留它，一个被授权管理「图片」目录的人
 * 就能去改「综合管理」的授权，等于越权。反过来若只留范围闸门，任何拿到完全控制的人
 * 都能给任意用户授完全控制，可以自我复制提权。两道闸门都在，才既能让多人分担维护，
 * 又不至于滚雪球。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/perm")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final AuditService auditService;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysDeptMapper sysDeptMapper;

    /**
     * 能力闸门用的权限串——沿用 V1.9.0 建库时就写好的两个按钮，不再另造新词：
     * 「权限分配」与「完全控制」在「角色管理 → 分配权限」里本来就是两个可勾选项，
     * 勾上任意一个即视为"这个人可以去做文档授权"，所以用 OR。
     */
    private static final String PERM_GRANT = "doc:perm:grant";
    private static final String PERM_FULL = "doc:perm:full";

    // 授权/撤销/查看授权清单都要求：① 持有 doc:perm:grant 或 doc:perm:full；② 对目标资源具备「完全控制」位（128）。
    // 只查 ① 会越权改别人的目录；只查 ② 会自我复制提权。

    /**
     * 查看文件夹已授权列表（需完全控制）
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @GetMapping("/folders/{folderId}")
    public R<List<DocFolderPermission>> listFolderPerms(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.FULL_CONTROL, userId);
        return R.ok(permissionService.listFolderPermissions(folderId, userId));
    }

    /**
     * 查看文件夹「实际生效」的权限（需完全控制）
     *
     * <p>含本层授权（sourceType=direct，可撤销）与继承自上级目录的授权
     * （sourceType=inherited，只读）。授权通常建在文档区或上级目录上，
     * 只看本层会让用户误以为「没有任何权限」。
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @GetMapping("/folders/{folderId}/effective")
    public R<List<Map<String, Object>>> listFolderEffective(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.FULL_CONTROL, userId);
        return R.ok(permissionService.listEffectivePermissions("folder", folderId, userId));
    }

    /**
     * 查看文件「实际生效」的权限（需完全控制）
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @GetMapping("/files/{fileId}/effective")
    public R<List<Map<String, Object>>> listFileEffective(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFile(fileId, PermissionFlag.FULL_CONTROL, userId);
        return R.ok(permissionService.listEffectivePermissions("file", fileId, userId));
    }

    /**
     * 文件夹授权（需完全控制）
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @PostMapping("/folders/{folderId}/grant")
    public R<Void> grantFolder(@PathVariable Long folderId, @RequestBody GrantPermissionRequest req) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.FULL_CONTROL, userId);
        req.setResourceType("folder");
        req.setResourceId(folderId);
        permissionService.grant(req, userId);
        auditService.record(AuditAction.PERM_GRANT, "FOLDER", folderId, null,
                grantDetail(req));
        return R.ok();
    }

    /**
     * 撤销文件夹权限（需完全控制）
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @DeleteMapping("/folders/{folderId}/revoke")
    public R<Void> revokeFolder(@PathVariable Long folderId,
                                @RequestParam String subjectType,
                                @RequestParam Long subjectId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.FULL_CONTROL, userId);
        permissionService.revoke("folder", folderId, subjectType, subjectId, userId);
        auditService.record(AuditAction.PERM_REVOKE, "FOLDER", folderId, null,
                revokeDetail(subjectType, subjectId));
        return R.ok();
    }

    /**
     * 查看文件已授权列表（需完全控制）
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @GetMapping("/files/{fileId}")
    public R<List<DocFilePermission>> listFilePerms(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFile(fileId, PermissionFlag.FULL_CONTROL, userId);
        return R.ok(permissionService.listFilePermissions(fileId, userId));
    }

    /**
     * 文件授权（需完全控制）
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @PostMapping("/files/{fileId}/grant")
    public R<Void> grantFile(@PathVariable Long fileId, @RequestBody GrantPermissionRequest req) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFile(fileId, PermissionFlag.FULL_CONTROL, userId);
        req.setResourceType("file");
        req.setResourceId(fileId);
        permissionService.grant(req, userId);
        auditService.record(AuditAction.PERM_GRANT, "FILE", fileId, null,
                grantDetail(req));
        return R.ok();
    }

    /**
     * 撤销文件权限（需完全控制）
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @DeleteMapping("/files/{fileId}/revoke")
    public R<Void> revokeFile(@PathVariable Long fileId,
                              @RequestParam String subjectType,
                              @RequestParam Long subjectId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFile(fileId, PermissionFlag.FULL_CONTROL, userId);
        permissionService.revoke("file", fileId, subjectType, subjectId, userId);
        auditService.record(AuditAction.PERM_REVOKE, "FILE", fileId, null,
                revokeDetail(subjectType, subjectId));
        return R.ok();
    }

    /**
     * 检查当前用户对资源的有效权限位
     */
    @GetMapping("/check")
    public R<Integer> check(@RequestParam String resourceType,
                            @RequestParam Long resourceId) {
        Long userId = LoginHelper.getUserId();
        // 超级管理员在这里也要返回满权限：computeUserFlags 里没有超管豁免分支
        // （豁免写在 requireFolder/requireFile 里），若文档区 owner 不是超管本人，
        // 会返回 0 导致前端把「权限设置」入口挡掉。
        // 注意这里返回的是"范围闸门"的答案；前端还要同时看自己有没有 dms:perm:config，
        // 两个条件都满足才显示入口——与后端两道闸门一一对应
        if (LoginHelper.isSuperAdmin()) {
            return R.ok(PermissionFlag.FULL);
        }
        int flags = permissionService.computeUserFlags(resourceType, resourceId, userId);
        return R.ok(flags);
    }

    /** 授权明细：谁被授了什么权限（审计里最需要看的就是这个） */
    private java.util.Map<String, Object> grantDetail(GrantPermissionRequest req) {
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("subjectType", req.getSubjectType());
        detail.put("subjectId", req.getSubjectId() == null ? null : String.valueOf(req.getSubjectId()));
        detail.put("permFlags", req.getPermFlags());
        detail.put("permText", describeFlags(req.getPermFlags()));
        detail.put("inheritToChildren", req.getInheritToChildren());
        detail.put("expiresAt", req.getExpiresAt());
        return detail;
    }

    private java.util.Map<String, Object> revokeDetail(String subjectType, Long subjectId) {
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("subjectType", subjectType);
        detail.put("subjectId", subjectId == null ? null : String.valueOf(subjectId));
        return detail;
    }

    /** 位掩码转可读文案，方便事后直接看日志 */
    private String describeFlags(Integer flags) {
        if (flags == null) {
            return "";
        }
        int f = flags;
        if ((f & PermissionFlag.DENY.getCode()) != 0) {
            return "禁止访问";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if ((f & PermissionFlag.VISIBLE.getCode()) != 0) parts.add("可见");
        if ((f & PermissionFlag.PREVIEW.getCode()) != 0) parts.add("预览");
        if ((f & PermissionFlag.DOWNLOAD.getCode()) != 0) parts.add("下载");
        if ((f & PermissionFlag.EDIT.getCode()) != 0) parts.add("编辑");
        if ((f & PermissionFlag.DELETE.getCode()) != 0) parts.add("删除");
        if ((f & PermissionFlag.UPLOAD.getCode()) != 0) parts.add("上传");
        if ((f & PermissionFlag.FULL_CONTROL.getCode()) != 0) parts.add("完全控制");
        return String.join("+", parts);
    }

    /**
     * 可授权主体候选（用户 / 角色 / 部门）
     *
     * <p>为什么不让前端继续直接调 RuoYi 的 {@code /system/user/list}：
     * 那三个接口要 {@code system:user:list} / {@code system:role:list} / {@code system:dept:list}，
     * 于是"只想让他给文档授权"就得连「用户管理」「角色管理」「部门管理」三个页面一起放开，
     * 被授权的人还会在自己菜单里看到这些他并不该操作的页面。
     * 授权弹窗真正需要的只是"一份可选主体名单"，所以在这里给一个最小出口：
     * 只要拿到授权能力（doc:perm:grant / doc:perm:full）就能读，返回字段也只到
     * 名字为止——不给邮箱、手机号、密码状态这些授权用不到的东西。
     *
     * <p>ID 一律转成字符串返回：雪花 ID 19 位超出 JS 的安全整数范围，
     * 走 JSON 数字会被前端 Number 截断，导致授权对象错人（踩过）。
     */
    @SaCheckPermission(value = {PERM_GRANT, PERM_FULL}, mode = SaMode.OR)
    @GetMapping("/subjects")
    public R<Map<String, Object>> subjects() {
        List<Map<String, Object>> users = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                    .select(SysUser::getUserId, SysUser::getUserName, SysUser::getNickName)
                    .eq(SysUser::getDelFlag, "0")
                    // 内置超管不需要被授权：他对所有资源都有满权限，出现在名单里只会让人误以为"没授他就看不到"
                    .ne(SysUser::getUserId, SystemConstants.SUPER_ADMIN_USER_ID)
                    .orderByAsc(SysUser::getUserId))
            .stream().map(u -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("userId", String.valueOf(u.getUserId()));
                m.put("userName", u.getUserName());
                m.put("nickName", u.getNickName());
                return m;
            }).toList();

        List<Map<String, Object>> roles = sysRoleMapper.selectList(
                new LambdaQueryWrapper<SysRole>()
                    .select(SysRole::getRoleId, SysRole::getRoleName)
                    .eq(SysRole::getDelFlag, "0")
                    // 同理：内置超管角色是空壳（sys_role_menu 里 0 条），授了也没有任何效果
                    .ne(SysRole::getRoleId, SystemConstants.SUPER_ADMIN_ROLE_ID)
                    .orderByAsc(SysRole::getRoleId))
            .stream().map(r -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("roleId", String.valueOf(r.getRoleId()));
                m.put("roleName", r.getRoleName());
                return m;
            }).toList();

        // 部门返回扁平列表（带 parentId）：前端下拉本来就是平铺展示，
        // 之前直接拿 /system/dept/list 的树、只取顶层，导致下级部门选不到
        List<Map<String, Object>> depts = sysDeptMapper.selectList(
                new LambdaQueryWrapper<SysDept>()
                    .select(SysDept::getDeptId, SysDept::getDeptName, SysDept::getParentId)
                    .eq(SysDept::getDelFlag, "0")
                    .orderByAsc(SysDept::getDeptId))
            .stream().map(d -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("deptId", String.valueOf(d.getDeptId()));
                m.put("deptName", d.getDeptName());
                m.put("parentId", String.valueOf(d.getParentId()));
                return m;
            }).toList();

        Map<String, Object> vo = new java.util.LinkedHashMap<>();
        vo.put("users", users);
        vo.put("roles", roles);
        vo.put("depts", depts);
        return R.ok(vo);
    }

    /**
     * 权限位常量（前端参考）
     */
    @GetMapping("/flags")
    public R<List<PermissionFlag>> flags() {
        return R.ok(List.of(PermissionFlag.values()));
    }
}

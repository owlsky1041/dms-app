package org.dromara.dms.doc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.service.ISysDeptService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前登录用户权限主体解析器
 *
 * <p>权限判定的主体集合 = user(本人) + role(全部角色) + dept(本部门 + ancestors 祖先链)。
 * 集中在此解析，避免各处实现不一致。
 *
 * <p>超级管理员返回 {@link Scope#unrestricted()} = true，调用方应跳过权限过滤。
 *
 * @author DMS
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionScopeResolver {

    private final ISysDeptService deptService;

    /**
     * 权限主体集合
     *
     * @param userId      当前用户
     * @param roleIds     角色 ID 列表
     * @param deptIds     部门 ID 列表（本部门 + 祖先链）
     * @param superAdmin  是否超级管理员（true 时不受权限限制）
     */
    public record Scope(Long userId, List<Long> roleIds, List<Long> deptIds, boolean superAdmin) {
        public boolean unrestricted() {
            return superAdmin;
        }
    }

    public Scope current() {
        Long userId = LoginHelper.getUserId();
        boolean superAdmin = LoginHelper.isSuperAdmin();
        return new Scope(userId, currentRoleIds(), currentDeptIds(), superAdmin);
    }

    /** 当前用户全部角色 ID */
    public List<Long> currentRoleIds() {
        List<Long> ids = new ArrayList<>();
        try {
            LoginUser user = LoginHelper.<LoginUser>getLoginUser();
            if (user != null && user.getRoles() != null) {
                user.getRoles().forEach(role -> {
                    if (role != null && role.getRoleId() != null && !ids.contains(role.getRoleId())) {
                        ids.add(role.getRoleId());
                    }
                });
            }
        } catch (Exception e) {
            log.debug("resolve roleIds failed", e);
        }
        return ids;
    }

    /**
     * 当前用户部门 ID 列表（本部门 + sys_dept.ancestors 祖先链）
     *
     * <p>部门授权需对子部门生效，因此必须带祖先链，否则在父部门上的授权不命中。
     */
    public List<Long> currentDeptIds() {
        List<Long> ids = new ArrayList<>();
        try {
            Long deptId = LoginHelper.getDeptId();
            if (deptId == null) {
                return ids;
            }
            ids.add(deptId);
            SysDeptVo dept = deptService.selectDeptById(deptId);
            if (dept != null && dept.getAncestors() != null) {
                for (String part : dept.getAncestors().split(",")) {
                    String s = part.trim();
                    if (s.isEmpty()) continue;
                    try {
                        Long id = Long.parseLong(s);
                        // 0 是虚拟根，不参与授权匹配
                        if (id != 0L && !ids.contains(id)) {
                            ids.add(id);
                        }
                    } catch (NumberFormatException ignored) {
                        // 忽略非法片段
                    }
                }
            }
        } catch (Exception e) {
            log.debug("resolve deptIds failed", e);
        }
        return ids;
    }

    /** 无登录上下文（定时任务等）时的空主体 */
    public Scope anonymous() {
        return new Scope(null, List.of(), List.of(), false);
    }
}

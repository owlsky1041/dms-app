package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.dms.doc.dto.IdNameRow;
import org.dromara.dms.doc.dto.SubjectNamesRequest;
import org.dromara.dms.doc.mapper.DocSubjectNameMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 主体名称解析 API
 *
 * <p>界面以「真实姓名」展示创建者/所有者/权限主体，底层仍以 ID 关联。
 * 前端把要显示的 ID 批量送过来，一次请求换回 id→name 映射。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/subjects")
@RequiredArgsConstructor
public class SubjectController {

    private final DocSubjectNameMapper subjectNameMapper;

    /**
     * 批量解析用户/角色/部门名称
     *
     * @return { users: {id: name}, roles: {...}, depts: {...} }
     */
    @PostMapping("/names")
    public R<Map<String, Map<String, String>>> names(@RequestBody SubjectNamesRequest req) {
        Map<String, Map<String, String>> result = new HashMap<>();
        List<Long> userIds = clean(req.getUserIds());
        List<Long> roleIds = clean(req.getRoleIds());
        List<Long> deptIds = clean(req.getDeptIds());
        // 空集合不能进 SQL（会生成非法的 IN ()），直接给空映射
        result.put("users", userIds.isEmpty() ? Map.of() : toMap(subjectNameMapper.selectUserNames(userIds)));
        result.put("roles", roleIds.isEmpty() ? Map.of() : toMap(subjectNameMapper.selectRoleNames(roleIds)));
        result.put("depts", deptIds.isEmpty() ? Map.of() : toMap(subjectNameMapper.selectDeptNames(deptIds)));
        return R.ok(result);
    }

    /**
     * 去重并剔除空值
     */
    private List<Long> clean(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private Map<String, String> toMap(List<IdNameRow> rows) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rows == null) {
            return map;
        }
        for (IdNameRow row : rows) {
            if (row.getId() != null) {
                map.put(String.valueOf(row.getId()), row.getName());
            }
        }
        return map;
    }
}

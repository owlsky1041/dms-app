package org.dromara.dms.doc.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 权限主体类型（被授权的对象）
 *
 * @author DMS
 */
@Getter
@AllArgsConstructor
public enum SubjectType {

    USER("user", "用户"),
    ROLE("role", "角色"),
    DEPT("dept", "部门");

    private final String code;
    private final String description;

    public static SubjectType of(String code) {
        for (SubjectType t : values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        throw new IllegalArgumentException("Unknown SubjectType: " + code);
    }
}

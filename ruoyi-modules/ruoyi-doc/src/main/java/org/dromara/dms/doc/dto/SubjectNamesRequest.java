package org.dromara.dms.doc.dto;

import lombok.Data;

import java.util.List;

/**
 * 主体名称解析请求
 *
 * <p>按需解析：只传界面真正要显示的 ID，避免把整个用户/角色/部门目录下发。
 *
 * @author DMS
 */
@Data
public class SubjectNamesRequest {

    /** 用户 ID 列表 */
    private List<Long> userIds;

    /** 角色 ID 列表 */
    private List<Long> roleIds;

    /** 部门 ID 列表 */
    private List<Long> deptIds;
}

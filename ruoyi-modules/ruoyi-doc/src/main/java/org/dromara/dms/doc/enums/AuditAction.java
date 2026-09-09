package org.dromara.dms.doc.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审计日志动作类型
 *
 * @author DMS
 */
@Getter
@AllArgsConstructor
public enum AuditAction {

    UPLOAD("上传"),
    DOWNLOAD("下载"),
    DELETE("删除"),
    MOVE("移动"),
    RENAME("重命名"),
    PREVIEW("预览"),
    EDIT("编辑元数据"),
    PERM_GRANT("授权"),
    PERM_REVOKE("撤销授权"),
    RESTORE("恢复"),
    PERMANENT_DELETE("永久删除"),
    SHARE("分享"),
    CREATE_FOLDER("创建文件夹");

    private final String description;
}

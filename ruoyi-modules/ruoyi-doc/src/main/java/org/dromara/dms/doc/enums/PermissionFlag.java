package org.dromara.dms.doc.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DMS 文档权限位枚举（位掩码）
 *
 * <p>每位表示一项权限，支持 OR 运算组合。例如"完全控制"=255（所有位都为1）。
 *
 * <p>对应数据库 doc_folder_permission.perm_flags 和 doc_file_permission.perm_flags。
 *
 * @author DMS
 */
@Getter
@AllArgsConstructor
public enum PermissionFlag {

    /** 位 0：可见（列出文件夹/文件） */
    VISIBLE(1, "可见"),

    /** 位 1：预览（打开预览窗口） */
    PREVIEW(2, "预览"),

    /** 位 2：编辑（修改元数据） */
    EDIT(4, "编辑"),

    /** 位 3：下载 */
    DOWNLOAD(8, "下载"),

    /** 位 4：删除 */
    DELETE(16, "删除"),

    /** 位 5：上传 */
    UPLOAD(32, "上传"),

    /** 位 6：创建子文件夹 */
    CREATE_CHILD(64, "创建子项"),

    /** 位 7：完全控制（修改权限） */
    FULL_CONTROL(128, "完全控制");

    private final int code;
    private final String description;

    /**
     * 检查权限位是否包含某项
     *
     * @param flags     当前位掩码
     * @param required  需要的权限位
     */
    public static boolean has(int flags, PermissionFlag required) {
        return (flags & required.code) != 0;
    }

    /**
     * 检查权限位是否包含所有指定项
     */
    public static boolean hasAll(int flags, PermissionFlag... required) {
        int mask = 0;
        for (PermissionFlag f : required) mask |= f.code;
        return (flags & mask) == mask;
    }

    /**
     * 检查权限位是否包含任一指定项
     */
    public static boolean hasAny(int flags, PermissionFlag... required) {
        int mask = 0;
        for (PermissionFlag f : required) mask |= f.code;
        return (flags & mask) != 0;
    }

    /**
     * 完全控制位掩码（所有位）
     */
    public static final int FULL = 255;
}

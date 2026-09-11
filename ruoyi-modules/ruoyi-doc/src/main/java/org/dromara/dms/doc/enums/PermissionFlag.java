package org.dromara.dms.doc.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DMS 文档权限位枚举（位掩码）
 *
 * <p>每位表示一项权限，支持 OR 运算组合。全部授予位 = {@link #FULL}（187）。
 * 其中 {@link #DENY}（禁止访问）是独立的拒绝位，不参与「完全控制」的合并。
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

    /** 位 3：下载 */
    DOWNLOAD(8, "下载"),

    /** 位 4：删除 */
    DELETE(16, "删除"),

    /** 位 5：上传（含在被授权目录下新建子文件夹） */
    UPLOAD(32, "上传"),

    /** 位 7：完全控制（修改权限、重命名、移动） */
    FULL_CONTROL(128, "完全控制"),

    /**
     * 位 8：禁止访问
     *
     * <p>这是一个「拒绝位」，与其他位语义相反：命中即完全不可见
     * （不出现在列表、搜索结果中，单资源访问一律拒绝），且向下继承。
     *
     * <p>优先级：文档区所有者与超级管理员不受其影响，其余主体「禁止优先于授予」。
     *
     * <p>取值 256（不复用已废弃的 4/64）—— 历史数据里 4=编辑、64=创建子项，
     * 复用会让旧数据被误解析成「禁止访问」。
     */
    DENY(256, "禁止访问");

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
     * 全部授予位掩码（不含禁止位）：可见+预览+下载+删除+上传+完全控制
     */
    public static final int FULL = 187;
}

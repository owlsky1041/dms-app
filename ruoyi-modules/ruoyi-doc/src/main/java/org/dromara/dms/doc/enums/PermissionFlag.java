package org.dromara.dms.doc.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DMS 文档权限位（位掩码）
 *
 * <p>对用户暴露的是 4 个<b>档位</b>加 1 个可叠加开关，底层仍然是这些位：
 * <pre>
 *   只读     READ_ONLY  = 可见 + 预览                         = 3
 *   读写     READ_WRITE = 只读 + 编辑 + 删除 + 上传            = 55
 *   完全控制 FULL       = 读写 + 下载 + 授权                   = 191
 *   下载     DOWNLOAD   = 可叠加在只读/读写之上                = 8
 *   禁止访问 DENY       = 拒绝位，与所有授予互斥                = 256
 * </pre>
 *
 * <p><b>档位必须是嵌套的</b>（{@code 只读 ⊂ 读写 ⊂ 完全控制}），因为多主体授权是取并集：
 * 只要嵌套成立，「禁止访问 &gt; 读写 &gt; 只读」这个优先级就自动满足，不需要额外写优先级比较。
 * 需要显式处理的只有「拒绝位压过一切」——见 {@code PermissionServiceImpl} 里 denied 分支。
 *
 * <p>位值沿用历史定义，历史数据不需要迁移。
 *
 * @author DMS
 */
@Getter
@AllArgsConstructor
public enum PermissionFlag {

    /** 位 0：可见（列出文件夹/文件）。任何档位都隐含它，不单独授予 */
    VISIBLE(1, "可见"),

    /** 位 1：预览（在线查看，含 OnlyOffice 只读打开） */
    PREVIEW(2, "预览"),

    /**
     * 位 2：编辑（改名、移动）
     *
     * <p>这个位原本叫「编辑」并在权限位精简时被停用，当时改名/移动改由「完全控制」承担。
     * 现在「读写」档需要移动能力但不能连带拿到授权能力，所以重新启用它。
     *
     * <p>兼容：判定时同时接受本位与 {@link #FULL_CONTROL}，
     * 这样历史数据里只有 128 的授权不会突然失去移动能力。
     */
    EDIT(4, "编辑"),

    /** 位 3：下载（把原件带走）。正交开关，可叠加在只读/读写之上 */
    DOWNLOAD(8, "下载"),

    /** 位 4：删除（移入回收站） */
    DELETE(16, "删除"),

    /** 位 5：上传（往目录里放文件，含新建子目录） */
    UPLOAD(32, "上传"),

    /**
     * 位 7：完全控制（给他人分配权限）
     *
     * <p>持有者可以对<b>这一个资源</b>做授权/撤销。它不是超管专属：
     * 内置超管可以把某一棵目录的完全控制授给某个角色（部门管理员维护本部门资料），
     * 但真正调用授权接口还要求额外的能力权限串，见 {@code PermissionController} 的"两道闸门"。
     */
    FULL_CONTROL(128, "完全控制"),

    /**
     * 位 8：禁止访问（拒绝位）
     *
     * <p>与其他位语义相反：命中即完全不可见（列表与搜索都不出现），且向下继承；
     * 文档区所有者与超级管理员不受其影响。优先级高于一切授予。
     *
     * <p>取值 256 —— 不复用历史位值，避免旧数据被误解析。
     */
    DENY(256, "禁止访问");

    private final int code;
    private final String description;

    // ==================== 档位定义 ====================

    /** 只读档：可见 + 预览 */
    public static final int READ_ONLY = 1 | 2;

    /** 读写档：只读 + 编辑 + 删除 + 上传 */
    public static final int READ_WRITE = READ_ONLY | 4 | 16 | 32;

    /** 全部授予位（不含拒绝位）：读写 + 下载 + 授权 = 191 */
    public static final int FULL = READ_WRITE | 8 | 128;

    /**
     * 可管理位（改名/移动）
     *
     * <p>取「编辑」或「完全控制」的并集：新数据用 EDIT，历史数据用 FULL_CONTROL。
     */
    public static final int MANAGE = 4 | 128;

    // ==================== 工具方法 ====================

    public static boolean has(int flags, PermissionFlag required) {
        return (flags & required.code) != 0;
    }

    public static boolean hasAll(int flags, PermissionFlag... required) {
        int mask = 0;
        for (PermissionFlag f : required) mask |= f.code;
        return (flags & mask) == mask;
    }

    public static boolean hasAny(int flags, PermissionFlag... required) {
        int mask = 0;
        for (PermissionFlag f : required) mask |= f.code;
        return (flags & mask) != 0;
    }

    /** 是否可改名/移动（编辑位或完全控制位任一命中即放行） */
    public static boolean canManage(int flags) {
        return (flags & MANAGE) != 0;
    }

    /**
     * 规格化即将落库的权限位
     *
     * <p>把两条「前提」做成数据层的不变式，而不是指望管理员别配错：
     * <ol>
     *   <li>禁止访问独占——带了拒绝位就只留拒绝位</li>
     *   <li>任何授予都隐含「可见」——否则这条授权在列表/搜索里根本不生效
     *       （列表 SQL 要求 {@code perm_flags & 1 <> 0}）</li>
     *   <li>「下载」隐含「预览」——能带走原件必然能看内容，
     *       否则会出现「能下载但在线打不开」这种自相矛盾的状态</li>
     * </ol>
     *
     * @param flags 原始位掩码
     * @return 规格化后的位掩码
     */
    public static int normalize(int flags) {
        if ((flags & DENY.code) != 0) {
            return DENY.code;
        }
        int out = flags & FULL;   // 丢弃未定义的位
        if (out == 0) {
            return 0;
        }
        out |= VISIBLE.code;
        if ((out & DOWNLOAD.code) != 0) {
            out |= PREVIEW.code;
        }
        return out;
    }
}

package org.dromara.dms.doc.service;

import java.io.OutputStream;
import java.time.LocalDateTime;

/**
 * 审计日志的查询/导出/清除
 *
 * <p>与 {@link AuditService}（负责"记一笔"）分开：这里是管理动作，只允许超管调用，
 * 且都要自己留痕。
 *
 * @author DMS
 */
public interface AuditAdminService {

    /**
     * 累积写出的行数上限（防止一次导出把内存/带宽打满）
     */
    int exportMaxRows();

    /**
     * 把匹配的审计日志以 CSV 流式写出
     *
     * <p>分批查询、边查边写：日志表可能几十万行，一次性 selectList 会 OOM。
     * 带 UTF-8 BOM，Excel 直接双击打开不乱码。
     *
     * @return 实际写出的数据行数
     */
    int exportCsv(OutputStream out, String action, Long userId,
                  LocalDateTime begin, LocalDateTime end);

    /**
     * 删除匹配的审计日志
     *
     * @param all true=清空全部（忽略其它条件），false=按条件删
     * @return 删除行数
     */
    int clear(String action, Long userId, LocalDateTime begin, LocalDateTime end, boolean all);

    /**
     * 统计匹配的行数（供前端在清除前展示"将要删除多少条"）
     */
    long count(String action, Long userId, LocalDateTime begin, LocalDateTime end);
}

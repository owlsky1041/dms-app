package org.dromara.dms.doc.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocAuditLog;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.mapper.DocAuditLogMapper;
import org.dromara.dms.doc.service.AuditAdminService;
import org.dromara.dms.doc.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务审计日志查询 API
 *
 * <p>查看与导出由权限串 {@code system:audit:list} / {@code system:audit:export} 控制——
 * 这两个能力可由内置超管授予其他角色，以便多人共同维护。
 * <b>清除日志仍然只有内置超管能做</b>：它不可逆，且会销毁追责证据，属于兜底层。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DocAuditLogMapper auditLogMapper;
    private final AuditAdminService auditAdminService;
    private final AuditService auditService;

    /**
     * 分页查询审计日志
     *
     * @param action   动作（DOWNLOAD / PERM_GRANT / PERM_REVOKE / PERMANENT_DELETE…），可空
     * @param userId   操作人，可空
     * @param beginDay 起始日期 yyyy-MM-dd（含当天），可空
     * @param endDay   结束日期 yyyy-MM-dd（含当天），可空
     */
    @SaCheckPermission("system:audit:list")
    @GetMapping("/list")
    public R<Map<String, Object>> list(@RequestParam(required = false) String action,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) String beginDay,
                                       @RequestParam(required = false) String endDay,
                                       @RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "20") long pageSize) {
        LambdaQueryWrapper<DocAuditLog> q = new LambdaQueryWrapper<DocAuditLog>()
                .eq(StringUtils.isNotBlank(action), DocAuditLog::getAction, action)
                .eq(userId != null, DocAuditLog::getUserId, userId)
                .ge(StringUtils.isNotBlank(beginDay), DocAuditLog::getCreatedAt, parseStart(beginDay))
                .le(StringUtils.isNotBlank(endDay), DocAuditLog::getCreatedAt, parseEnd(endDay))
                .orderByDesc(DocAuditLog::getLogId);

        Page<DocAuditLog> page = auditLogMapper.selectPage(new Page<>(pageNum, pageSize), q);

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("rows", page.getRecords());
        vo.put("total", page.getTotal());
        vo.put("pageNum", page.getCurrent());
        vo.put("pageSize", page.getSize());
        return R.ok(vo);
    }

    /** 动作枚举清单（给前端下拉用，避免前端硬编码） */
    @SaCheckPermission("system:audit:list")
    @GetMapping("/actions")
    public R<List<Map<String, String>>> actions() {
        return R.ok(java.util.Arrays.stream(org.dromara.dms.doc.enums.AuditAction.values())
                .map(a -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("code", a.name());
                    m.put("label", a.getDescription());
                    return m;
                })
                .toList());
    }

    /**
     * 导出审计日志（CSV）
     *
     * <p>筛选条件与 /list 完全一致：导出的就是"当前看到的这些"。
     * 流式写出，不落临时文件；每 2000 行一批查，几十万行也不会 OOM。
     */
    @SaCheckPermission("system:audit:export")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String action,
                       @RequestParam(required = false) Long userId,
                       @RequestParam(required = false) String beginDay,
                       @RequestParam(required = false) String endDay,
                       HttpServletResponse response) throws IOException {
        LocalDateTime begin = parseStart(beginDay);
        LocalDateTime end = parseEnd(endDay);

        String fileName = "审计日志-" + FILE_TS.format(LocalDateTime.now()) + ".csv";
        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"audit-log.csv\"; filename*=UTF-8''"
                        + URLEncoder.encode(fileName, StandardCharsets.UTF_8));

        int rows = auditAdminService.exportCsv(
                response.getOutputStream(), action, userId, begin, end);

        // 导出动作本身要留痕：日志给谁看过、被谁整批带走，是最该被审计的事
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rows", rows);
        detail.put("action", action);
        detail.put("beginDay", beginDay);
        detail.put("endDay", endDay);
        detail.put("maxRows", auditAdminService.exportMaxRows());
        auditService.record(AuditAction.AUDIT_EXPORT, "AUDIT_LOG", null, fileName, detail);
        log.info("导出审计日志: {} 行, 筛选 action={}, {} ~ {}", rows, action, beginDay, endDay);
    }

    /** 清除前先告诉前端"会删掉多少条"，避免一键清空时心里没数 */
    @SaCheckPermission("system:audit:list")
    @GetMapping("/count")
    public R<Long> count(@RequestParam(required = false) String action,
                         @RequestParam(required = false) Long userId,
                         @RequestParam(required = false) String beginDay,
                         @RequestParam(required = false) String endDay) {
        return R.ok(auditAdminService.count(action, userId, parseStart(beginDay), parseEnd(endDay)));
    }

    /**
     * 清除审计日志
     *
     * <p>用 POST 而不是 DELETE：DELETE 带请求体虽然在规范里合法，但中间层
     * （网关/代理）丢掉 body 的情况不少见，而这是不可逆操作，宁可走稳的路。
     *
     * <p>all=true 清空全部；否则按条件删（与 /list 同一套条件）。
     * 删除后补记一条 AUDIT_CLEAR —— 如果先记再删，这条记录自己也会被删掉，
     * 那就成了"日志被清过但查不到是谁清的"。
     */
    @PostMapping("/clear")
    public R<Map<String, Object>> clear(@RequestBody(required = false) ClearRequest req) {
        requireSuperAdmin();
        ClearRequest r = req == null ? ClearRequest.empty() : req;
        LocalDateTime begin = parseStart(r.beginDay());
        LocalDateTime end = parseEnd(r.endDay());

        long before = auditAdminService.count(r.action(), r.userId(), begin, end);
        boolean all = r.allOf();
        int deleted = auditAdminService.clear(r.action(), r.userId(), begin, end, all);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deleted", deleted);
        detail.put("scope", all ? "ALL" : "FILTERED");
        detail.put("action", r.action());
        detail.put("beginDay", r.beginDay());
        detail.put("endDay", r.endDay());
        detail.put("matchedBefore", before);
        auditService.record(AuditAction.AUDIT_CLEAR, "AUDIT_LOG", null,
                all ? "全部审计日志" : "按条件清除", detail);

        log.warn("清除审计日志: 删除 {} 条 (范围={}, action={}, 用户={})",
                deleted, all ? "全部" : "按条件", r.action(), LoginHelper.getUserId());

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("deleted", deleted);
        vo.put("matchedBefore", before);
        return R.ok(vo);
    }

    /**
     * 清除请求体（json 字段名与 /list 的查询参数保持一致）
     *
     * <p>字段一律用包装类型：RuoYi 的 Jackson 开了 FAIL_ON_NULL_FOR_PRIMITIVES，
     * 前端只传 {"action":"RENAME"} 时会把 null 塞进 boolean 参数，
     * 直接 400「请求参数格式错误」——踩过。
     */
    public record ClearRequest(String action, Long userId, String beginDay, String endDay, Boolean all) {

        public boolean allOf() {
            return Boolean.TRUE.equals(all);
        }

        /** 空体请求：等价于"不带任何条件"，即清空全部 */
        public static ClearRequest empty() {
            return new ClearRequest(null, null, null, null, false);
        }
    }

    /**
     * 兜底层校验：仅内置超管
     *
     * <p>只用于「清除日志」这类不可逆/涉及追责证据的操作。
     * 判断的是用户 ID（SystemConstants.SUPER_ADMIN_USER_ID），不是角色——
     * 这样即使有人误删了角色或授权数据，这个账号依然能进系统救场。
     */
    private void requireSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            throw new ServiceException("清除审计日志仅限内置超级管理员");
        }
    }

    private LocalDateTime parseStart(String day) {
        try {
            return LocalDate.parse(day).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseEnd(String day) {
        try {
            return LocalDate.parse(day).atTime(LocalTime.MAX);
        } catch (Exception e) {
            return null;
        }
    }
}

package org.dromara.dms.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocAuditLog;
import org.dromara.dms.doc.mapper.DocAuditLogMapper;
import org.dromara.dms.doc.service.AuditAdminService;
import org.dromara.system.domain.SysUser;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 审计日志导出 / 清除
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditAdminServiceImpl implements AuditAdminService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 分批查询的批大小：一次 2000 行，几十万行也不会把堆撑爆 */
    private static final int BATCH = 2000;

    /** Excel 认 BOM 才不会把 UTF-8 中文当乱码 */
    private static final String UTF8_BOM = "\uFEFF";

    private static final String[] HEADERS = {
            "时间", "操作人ID", "操作人", "动作", "对象类型", "对象ID", "名称/路径", "IP", "User-Agent", "详情"
    };

    private final DocAuditLogMapper auditLogMapper;
    private final SysUserMapper sysUserMapper;

    @Value("${dms.audit.export-max-rows:200000}")
    private int exportMaxRows;

    @Override
    public int exportMaxRows() {
        return exportMaxRows;
    }

    @Override
    public int exportCsv(OutputStream out, String action, Long userId,
                         LocalDateTime begin, LocalDateTime end) {
        // 名字解析放在服务端做：前端只拿到 userId 是看不懂的
        NameResolver names = new NameResolver();
        int written = 0;
        try {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write(UTF8_BOM);
            w.write(csvLine(HEADERS));
            // 按 log_id 升序导出：日志文件读起来要按时间顺序
            long pageNum = 1;
            while (written < exportMaxRows) {
                LambdaQueryWrapper<DocAuditLog> q = buildQuery(action, userId, begin, end)
                        .orderByAsc(DocAuditLog::getLogId);
                // searchCount=false：分批拉取时每批都 count 一次纯属浪费
                Page<DocAuditLog> page = auditLogMapper.selectPage(
                        new Page<>(pageNum, BATCH, false), q);
                List<DocAuditLog> rows = page.getRecords();
                if (rows.isEmpty()) {
                    break;
                }
                names.preload(rows.stream().map(DocAuditLog::getUserId).toList());
                for (DocAuditLog r : rows) {
                    if (written >= exportMaxRows) {
                        break;
                    }
                    w.write(toCsvRow(r, names));
                    written++;
                }
                w.flush();
                if (rows.size() < BATCH) {
                    break;
                }
                pageNum++;
            }
            w.flush();
        } catch (IOException e) {
            // 客户端中途取消下载会走到这里，不算错误
            log.warn("导出审计日志中断: 已写 {} 行, {}", written, e.getMessage());
        }
        return written;
    }

    @Override
    public int clear(String action, Long userId, LocalDateTime begin, LocalDateTime end, boolean all) {
        if (all) {
            // 清空：用 log_id > 0 全匹配，避免拼 "1=1" 这类字符串
            return auditLogMapper.delete(new LambdaQueryWrapper<DocAuditLog>()
                    .gt(DocAuditLog::getLogId, 0L));
        }
        return auditLogMapper.delete(buildQuery(action, userId, begin, end));
    }

    @Override
    public long count(String action, Long userId, LocalDateTime begin, LocalDateTime end) {
        Long n = auditLogMapper.selectCount(buildQuery(action, userId, begin, end));
        return n == null ? 0L : n;
    }

    /** 查询条件：list / export / clear / count 共用，保证"看到的"和"导出的/删掉的"完全一致 */
    private LambdaQueryWrapper<DocAuditLog> buildQuery(String action, Long userId,
                                                       LocalDateTime begin, LocalDateTime end) {
        return new LambdaQueryWrapper<DocAuditLog>()
                .eq(action != null && !action.isBlank(), DocAuditLog::getAction, action)
                .eq(userId != null, DocAuditLog::getUserId, userId)
                .ge(begin != null, DocAuditLog::getCreatedAt, begin)
                .le(end != null, DocAuditLog::getCreatedAt, end);
    }

    private String toCsvRow(DocAuditLog r, NameResolver names) {
        return csvLine(new String[]{
                r.getCreatedAt() == null ? "" : TS.format(r.getCreatedAt()),
                r.getUserId() == null ? "" : String.valueOf(r.getUserId()),
                names.nameOf(r.getUserId()),
                r.getAction() == null ? "" : r.getAction(),
                r.getResourceType() == null ? "" : r.getResourceType(),
                r.getResourceId() == null ? "" : String.valueOf(r.getResourceId()),
                r.getResourcePath() == null ? "" : r.getResourcePath(),
                r.getIp() == null ? "" : r.getIp(),
                r.getUserAgent() == null ? "" : r.getUserAgent(),
                r.getDetail() == null ? "" : r.getDetail()
        });
    }

    /**
     * CSV 转义
     *
     * <p>detail 里是 JSON，必然带逗号和引号；resourcePath 里也可能有逗号。
     * 不转义的话列会错位，Excel 打开就散了。
     */
    private String csvLine(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i]));
        }
        return sb.append("\r\n").toString();
    }

    /**
     * userId → 姓名 的批量解析
     *
     * <p>导出的 CSV 是要拿给审计看的，只给一串雪花 ID 等于没写。
     * 按批查一次 sys_user 再缓存，避免每行一次查询。
     */
    private final class NameResolver {

        private final Map<Long, String> cache = new HashMap<>();
        private final Set<Long> missing = new HashSet<>();

        String nameOf(Long userId) {
            if (userId == null) {
                return "";
            }
            if (cache.containsKey(userId)) {
                return cache.get(userId);
            }
            if (missing.contains(userId)) {
                return String.valueOf(userId);
            }
            return String.valueOf(userId);
        }

        /** 导出前先把这批行里出现过的用户一次性查回来 */
        void preload(Collection<Long> userIds) {
            List<Long> todo = new ArrayList<>();
            for (Long id : userIds) {
                if (id != null && !cache.containsKey(id) && !missing.contains(id)) {
                    todo.add(id);
                }
            }
            if (todo.isEmpty()) {
                return;
            }
            try {
                List<SysUser> users = sysUserMapper.lambda()
                        .in(SysUser::getUserId, todo)
                        .list();
                for (SysUser u : users) {
                    cache.put(u.getUserId(), display(u));
                }
                for (Long id : todo) {
                    if (!cache.containsKey(id)) {
                        // 用户已被删除：至少给个可读的占位，别让整列是空的
                        cache.put(id, "已删除用户(" + id + ")");
                    }
                }
            } catch (Exception e) {
                log.warn("导出审计日志时解析用户名失败，降级为显示 ID: {}", e.getMessage());
                missing.addAll(todo);
            }
        }

        private String display(SysUser u) {
            String nick = u.getNickName();
            String name = u.getUserName();
            if (nick != null && !nick.isBlank()) {
                return name != null && !name.isBlank() ? nick + "(" + name + ")" : nick;
            }
            return name == null ? String.valueOf(u.getUserId()) : name;
        }
    }

    private String escape(String v) {
        String s = v == null ? "" : v;
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needQuote) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}

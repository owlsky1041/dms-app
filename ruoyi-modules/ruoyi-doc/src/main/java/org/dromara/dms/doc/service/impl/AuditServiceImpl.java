package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocAuditLog;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.mapper.DocAuditLogMapper;
import org.dromara.dms.doc.service.AuditService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 业务审计服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    /** 字段长度上限（与建表语句一致），超长截断避免插入失败 */
    private static final int MAX_PATH = 500;
    private static final int MAX_UA = 500;
    private static final int MAX_IP = 64;

    private final DocAuditLogMapper auditLogMapper;

    @Override
    public void record(AuditAction action, String resourceType, Long resourceId,
                       String resourcePath, Map<String, Object> detail) {
        recordAs(safeUserId(), action, resourceType, resourceId, resourcePath, detail);
    }

    @Override
    public void recordAs(Long userId, AuditAction action, String resourceType, Long resourceId,
                         String resourcePath, Map<String, Object> detail) {
        try {
            DocAuditLog row = new DocAuditLog()
                    .setUserId(userId)
                    .setAction(action == null ? "UNKNOWN" : action.name())
                    .setResourceType(resourceType)
                    .setResourceId(resourceId)
                    .setResourcePath(truncate(resourcePath, MAX_PATH))
                    .setIp(truncate(resolveIp(), MAX_IP))
                    .setUserAgent(truncate(resolveUserAgent(), MAX_UA))
                    .setDetail(detail == null || detail.isEmpty() ? null : JsonUtils.toJsonString(detail))
                    .setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(row);
        } catch (Exception e) {
            // 审计是旁路：写失败只告警，绝不能让下载/授权这类业务因为日志失败而失败
            log.error("写审计日志失败: action={}, resourceType={}, resourceId={}, user={}",
                    action, resourceType, resourceId, safeUserId(), e);
        }
    }

    private Long safeUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveIp() {
        try {
            return ServletUtils.getClientIP();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveUserAgent() {
        try {
            return ServletUtils.getRequest().getHeader("User-Agent");
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (StringUtils.isBlank(s)) {
            return s;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

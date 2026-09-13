package org.dromara.dms.doc.service;

import org.dromara.dms.doc.enums.AuditAction;

import java.util.Map;

/**
 * 业务审计服务
 *
 * <p>只负责"记一笔"，不参与业务判断。约定：<b>写审计失败绝不能影响业务</b>——
 * 记录本身抛异常时只打日志，不向调用方传播。
 *
 * @author DMS
 */
public interface AuditService {

    /**
     * 记录一条审计日志
     *
     * @param action       动作
     * @param resourceType FOLDER / FILE
     * @param resourceId   资源 ID（可为 null）
     * @param resourcePath 资源路径（可为 null）
     * @param detail       详情（会序列化成 JSON，可为 null）
     */
    void record(AuditAction action, String resourceType, Long resourceId,
                String resourcePath, Map<String, Object> detail);

    /**
     * 便捷方法：无详情
     */
    default void record(AuditAction action, String resourceType, Long resourceId, String resourcePath) {
        record(action, resourceType, resourceId, resourcePath, null);
    }

    /**
     * 以指定用户身份记录（供后台线程使用）
     *
     * <p>异步打包这类后台线程没有 Sa-Token 上下文，{@code LoginHelper.getUserId()} 取不到人，
     * 必须由调用方把发起人 ID 传进来，否则审计会记成"无用户"。
     */
    void recordAs(Long userId, AuditAction action, String resourceType, Long resourceId,
                  String resourcePath, Map<String, Object> detail);
}

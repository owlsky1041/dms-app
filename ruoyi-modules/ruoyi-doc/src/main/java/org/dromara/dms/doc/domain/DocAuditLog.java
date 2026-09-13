package org.dromara.dms.doc.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务审计日志（doc_audit_log）
 *
 * <p>与 RuoYi 的 {@code sys_oper_log} 互补：后者是框架级通用日志，
 * 这张表记的是文档业务里真正需要追责/追溯的敏感动作
 * （下载、打包下载、授权/撤销、删除、永久删除）。
 *
 * <p>表只写不改不删，主键用 BIGSERIAL（不是雪花），因此这里显式指定 {@link IdType#AUTO}。
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("doc_audit_log")
public class DocAuditLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;

    /** 操作人 */
    private Long userId;

    /** 动作：见 {@code AuditAction} */
    private String action;

    /** 资源类型：FOLDER / FILE */
    private String resourceType;

    /** 资源 ID */
    private Long resourceId;

    /** 资源路径（如 /综合管理/图纸/） */
    private String resourcePath;

    /** 客户端 IP */
    private String ip;

    /** User-Agent */
    private String userAgent;

    /** JSON 详情 */
    private String detail;

    private LocalDateTime createdAt;
}

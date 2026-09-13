package org.dromara.dms.doc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.dms.doc.domain.DocAuditLog;

/**
 * 业务审计日志 Mapper
 *
 * @author DMS
 */
@Mapper
public interface DocAuditLogMapper extends BaseMapperPlus<DocAuditLog, DocAuditLog> {
}

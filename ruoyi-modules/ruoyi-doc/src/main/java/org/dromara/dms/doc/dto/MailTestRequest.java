package org.dromara.dms.doc.dto;

import lombok.Data;

/**
 * 邮件配置连通性测试请求
 *
 * @author DMS
 */
@Data
public class MailTestRequest {

    /** 测试收件人邮箱 */
    private String to;
}

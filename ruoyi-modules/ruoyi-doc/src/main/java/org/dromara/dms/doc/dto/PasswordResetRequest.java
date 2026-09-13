package org.dromara.dms.doc.dto;

import lombok.Data;

/**
 * 找回密码请求
 *
 * <p>{@code account} 可以是登录账号，也可以是账号绑定的邮箱。
 *
 * @author DMS
 */
@Data
public class PasswordResetRequest {

    /** 登录账号或邮箱 */
    private String account;

    /** 邮件验证码（申请验证码时不需要） */
    private String code;

    /** 新密码（申请验证码时不需要） */
    private String password;

    /** 确认新密码 */
    private String confirmPassword;
}

package org.dromara.dms.doc.service;

import org.dromara.dms.doc.dto.PasswordResetRequest;
import org.dromara.dms.doc.dto.SiteRegisterRequest;

/**
 * 站点账号自助服务：用户注册 / 邮箱验证码找回密码
 *
 * <p>开关与 SMTP 配置均来自「系统管理 → 站点配置」（{@code sys_site_config}），
 * 对应接口在 {@code security.excludes} 白名单中，允许匿名访问。
 *
 * @author DMS
 */
public interface SiteAuthService {

    /**
     * 用户自助注册
     *
     * @param req 注册信息
     */
    void register(SiteRegisterRequest req);

    /**
     * 申请找回密码验证码（发送到账号绑定邮箱）
     *
     * @param account 登录账号或邮箱
     * @return 验证码有效期（分钟）与脱敏后的收件邮箱
     */
    ResetCodeResult sendResetCode(String account);

    /**
     * 使用邮件验证码重置密码
     *
     * @param req 账号 + 验证码 + 新密码
     */
    void resetPassword(PasswordResetRequest req);

    /**
     * 申请验证码的结果
     *
     * @param validMinutes 验证码有效期（分钟）
     * @param maskedEmail  脱敏后的收件邮箱，例如 {@code zh***@example.com}
     */
    record ResetCodeResult(int validMinutes, String maskedEmail) {
    }
}

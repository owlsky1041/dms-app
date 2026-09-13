package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.SysSiteConfig;

/**
 * 站点邮件发送服务
 *
 * <p>SMTP 参数来自「系统管理 → 站点配置」中的邮件配置（存于 {@code sys_site_config}），
 * 而不是配置文件，因此这里每次发送都按当前数据库配置构建邮件账户，
 * 管理员改完配置即刻生效、无需重启。
 *
 * @author DMS
 */
public interface SiteMailService {

    /**
     * 给定配置是否具备发送条件（邮件开关已打开且必填项齐全）
     *
     * <p>调用方通常已经读过配置，传入即可，避免重复查询数据库。
     *
     * @param config 站点配置
     */
    boolean isReady(SysSiteConfig config);

    /**
     * 当前配置是否具备发送条件（自行读取站点配置）
     */
    boolean isReady();

    /**
     * 发送一封 HTML 邮件
     *
     * @param to      收件人
     * @param subject 主题
     * @param html    正文（HTML）
     * @throws org.dromara.common.core.exception.ServiceException 未配置或发送失败时抛出
     */
    void sendHtml(String to, String subject, String html);

    /**
     * 发送密码重置验证码邮件
     *
     * @param to           收件人
     * @param code         验证码
     * @param validMinutes 有效期（分钟）
     */
    void sendPasswordResetCode(String to, String code, int validMinutes);
}

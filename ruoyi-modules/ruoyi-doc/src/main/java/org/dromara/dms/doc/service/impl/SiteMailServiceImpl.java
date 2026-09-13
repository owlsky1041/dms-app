package org.dromara.dms.doc.service.impl;

import cn.hutool.extra.mail.MailAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mail.core.MailBuilder;
import org.dromara.dms.doc.domain.SysSiteConfig;
import org.dromara.dms.doc.service.SiteConfigService;
import org.dromara.dms.doc.service.SiteMailService;
import org.springframework.stereotype.Service;

/**
 * 站点邮件发送服务实现
 *
 * <p>不使用 RuoYi 的全局 {@code MailAccount} Bean：该 Bean 只在
 * {@code mail.enabled=true} 时创建，而本项目把 SMTP 配置放在数据库里由管理员在
 * 界面维护，因此这里按当前配置即时构建账户。
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteMailServiceImpl implements SiteMailService {

    /** 连接与读取超时（毫秒）：避免 SMTP 不通时接口长时间挂住 */
    private static final long TIMEOUT_MS = 10_000L;

    private final SiteConfigService siteConfigService;

    @Override
    public boolean isReady(SysSiteConfig config) {
        return Boolean.TRUE.equals(config.getMailEnabled())
                && notBlank(config.getMailHost())
                && config.getMailPort() != null && config.getMailPort() > 0
                && notBlank(config.getMailUsername())
                && notBlank(config.getMailPassword())
                && notBlank(config.getMailFrom());
    }

    @Override
    public boolean isReady() {
        return isReady(siteConfigService.get());
    }

    @Override
    public void sendHtml(String to, String subject, String html) {
        if (to == null || to.isBlank()) {
            throw new ServiceException("收件人邮箱不能为空");
        }
        SysSiteConfig config = siteConfigService.get();
        if (!Boolean.TRUE.equals(config.getMailEnabled())) {
            throw new ServiceException("系统未启用邮件发送，请先在「站点配置」中开启并填写 SMTP 信息");
        }
        MailAccount account = buildAccount(config);
        try {
            MailBuilder.of(account)
                    .to(to.trim())
                    .subject(subject)
                    .html(html)
                    .send();
            log.info("邮件已发送: to={}, subject={}", to, subject);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("邮件发送失败: to={}, subject={}", to, subject, e);
            throw new ServiceException("邮件发送失败：" + rootMessage(e));
        }
    }

    @Override
    public void sendPasswordResetCode(String to, String code, int validMinutes) {
        String siteName = siteConfigService.get().getSiteName();
        String safeSite = escapeHtml(siteName);
        String html = """
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,'PingFang SC','Microsoft YaHei',sans-serif;font-size:14px;color:#303133;line-height:1.7">
                  <p>您好，</p>
                  <p>您正在<b>%s</b>上申请重置登录密码，本次验证码为：</p>
                  <p style="margin:18px 0">
                    <span style="display:inline-block;padding:10px 22px;background:#f2f6fc;border:1px solid #d9ecff;border-radius:6px;font-size:26px;font-weight:700;letter-spacing:6px;color:#409eff">%s</span>
                  </p>
                  <p>验证码 <b>%d 分钟</b>内有效，请勿转发给他人。</p>
                  <p style="color:#909399;font-size:12px">如果这不是您本人的操作，请忽略本邮件，您的密码不会被修改。</p>
                </div>
                """.formatted(safeSite, code, validMinutes);
        sendHtml(to, "【" + siteName + "】密码重置验证码：" + code, html);
    }

    /**
     * 按数据库中的站点配置构建邮件账户
     */
    private MailAccount buildAccount(SysSiteConfig config) {
        MailAccount account = new MailAccount();
        account.setHost(config.getMailHost());
        account.setPort(config.getMailPort());
        account.setAuth(true);
        account.setFrom(buildFrom(config));
        account.setUser(config.getMailUsername());
        account.setPass(config.getMailPassword());
        // hutool 会把 socketFactoryPort 透传给 mail.smtp.socketFactory.port
        account.setSocketFactoryPort(config.getMailPort());
        account.setTimeout(TIMEOUT_MS);
        account.setConnectionTimeout(TIMEOUT_MS);
        String encrypt = config.getMailEncrypt() == null ? "ssl" : config.getMailEncrypt().toLowerCase();
        switch (encrypt) {
            case "ssl" -> {
                account.setSslEnable(true);
                account.setStarttlsEnable(false);
            }
            case "starttls" -> {
                account.setSslEnable(false);
                account.setStarttlsEnable(true);
            }
            default -> {
                // none：明文（内网自建邮件网关常用），端口通常在 25 / 1025
                account.setSslEnable(false);
                account.setStarttlsEnable(false);
            }
        }
        // 部分自建服务使用自签证书，信任所有主机可避免 handshake 失败
        account.setCustomProperty("mail.smtp.ssl.trust", config.getMailHost());
        return account;
    }

    /**
     * 拼装 RFC-822 发件人：有显示名时输出 {@code 名称 <邮箱>}
     *
     * <p>显示名与邮箱在库中是两列，故意不在请求体里收完整 RFC-822 串 ——
     * RuoYi 的 XssFilter 会把 {@code <...>} 当作 HTML 标签剥掉，导致发件人只剩显示名。
     */
    private String buildFrom(SysSiteConfig config) {
        String address = config.getMailFrom() == null ? "" : config.getMailFrom().trim();
        String name = config.getMailFromName() == null ? "" : config.getMailFromName().trim();
        if (name.isEmpty()) {
            return address;
        }
        // 显示名里的引号与反斜杠需要转义，否则 JavaMail 解析会出错
        String safeName = name.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safeName + "\" <" + address + ">";
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 取最内层异常信息，JavaMail 的顶层信息往往只有 "Mail server connection failed" */
    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg;
    }

    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}

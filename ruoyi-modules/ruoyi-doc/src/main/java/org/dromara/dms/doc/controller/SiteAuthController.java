package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.dto.MailTestRequest;
import org.dromara.dms.doc.dto.PasswordResetRequest;
import org.dromara.dms.doc.dto.SiteRegisterRequest;
import org.dromara.dms.doc.service.SiteAuthService;
import org.dromara.dms.doc.service.SiteConfigService;
import org.dromara.dms.doc.service.SiteMailService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点账号自助接口：用户注册 / 邮箱验证码找回密码 / 邮件配置测试
 *
 * <p>注册与找回密码必须允许匿名访问（用户还没登录），这三个路径已加入
 * {@code application.yml} 的 {@code security.excludes} 白名单。
 * 邮件测试接口需要超级管理员登录。
 *
 * <p>安全性说明：本接口面向内网系统，账号不存在 / 未绑定邮箱等在找回流程中
 * 直接给出明确提示以方便用户自助处理；验证码本身 5 分钟有效、同账号 60 秒内
 * 只能发送一次、连续错 5 次作废。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/site")
@RequiredArgsConstructor
public class SiteAuthController {

    private final SiteAuthService siteAuthService;
    private final SiteConfigService siteConfigService;
    private final SiteMailService siteMailService;

    /** 用户自助注册（公开） */
    @PostMapping("/register")
    public R<Void> register(@RequestBody SiteRegisterRequest req) {
        siteAuthService.register(req);
        return R.ok("注册成功，请使用新账号登录");
    }

    /** 申请找回密码验证码（公开） */
    @PostMapping("/password/code")
    public R<Map<String, Object>> sendResetCode(@RequestBody PasswordResetRequest req) {
        SiteAuthService.ResetCodeResult result = siteAuthService.sendResetCode(req.getAccount());
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("validMinutes", result.validMinutes());
        vo.put("maskedEmail", result.maskedEmail());
        vo.put("message", "验证码已发送至 " + result.maskedEmail() + "，" + result.validMinutes() + " 分钟内有效");
        return R.ok(vo);
    }

    /** 使用验证码重置密码（公开） */
    @PostMapping("/password/reset")
    public R<Void> resetPassword(@RequestBody PasswordResetRequest req) {
        siteAuthService.resetPassword(req);
        return R.ok("密码已重置，请使用新密码登录");
    }

    /** 发送测试邮件（仅超级管理员），用于验证 SMTP 配置是否正确 */
    @PostMapping("/mail/test")
    public R<Void> testMail(@RequestBody MailTestRequest req) {
        if (!LoginHelper.isSuperAdmin()) {
            throw new ServiceException("仅超级管理员可测试邮件配置");
        }
        String to = req == null || req.getTo() == null ? "" : req.getTo().trim();
        if (to.isEmpty()) {
            throw new ServiceException("请填写测试收件人邮箱");
        }
        String siteName = siteConfigService.get().getSiteName();
        siteMailService.sendHtml(to, "【" + siteName + "】邮件配置测试",
                """
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,'PingFang SC','Microsoft YaHei',sans-serif;font-size:14px;color:#303133;line-height:1.7">
                  <p>这是一封来自 <b>%s</b> 的测试邮件。</p>
                  <p>收到本邮件说明「系统管理 → 站点配置」中的邮件（SMTP）配置已可用，
                     用户可以正常使用邮箱验证码找回密码。</p>
                  <p style="color:#909399;font-size:12px">如非本人操作，请忽略。</p>
                </div>
                """.formatted(siteName));
        return R.ok("测试邮件已发送，请查收");
    }
}

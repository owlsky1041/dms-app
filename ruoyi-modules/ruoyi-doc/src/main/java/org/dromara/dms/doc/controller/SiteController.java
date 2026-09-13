package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.SysSiteConfig;
import org.dromara.dms.doc.dto.SiteConfigRequest;
import org.dromara.dms.doc.service.SiteConfigService;
import org.dromara.dms.doc.service.SiteMailService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 站点配置 API
 *
 * <p>读接口（配置、图标）必须允许匿名访问 —— 登录页在登录前就要展示站点名称、
 * 备案/版权信息和站点图标，因此这两个路径已加入 application.yml 的
 * {@code security.excludes} 白名单。
 *
 * <p>写接口仅超级管理员可用。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/site")
@RequiredArgsConstructor
public class SiteController {

    private final SiteConfigService siteConfigService;
    private final SiteMailService siteMailService;

    /** 站点配置（公开：登录页需要） */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return R.ok(toPublicVo(siteConfigService.get()));
    }

    /**
     * 站点配置完整视图（仅超级管理员）
     *
     * <p>公开的 {@code /config} 不能带 SMTP 账号密码，管理页需要回显这些字段，
     * 因此单独提供一个鉴权后的接口；密码只返回「是否已设置」，不回传明文。
     */
    @GetMapping("/admin-config")
    public R<Map<String, Object>> adminConfig() {
        requireSuperAdmin();
        return R.ok(toAdminVo(siteConfigService.get()));
    }

    /** 站点图标（公开），未配置时 404，前端保留默认图标 */
    @GetMapping("/favicon")
    public ResponseEntity<byte[]> favicon() {
        byte[] bytes = siteConfigService.readFavicon();
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        SysSiteConfig config = siteConfigService.get();
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(config.getFavicon()))
                .header("Cache-Control", "no-cache")
                .body(bytes);
    }

    /** 站点标识图（公开），未配置时 404，前端保留默认图标 */
    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo() {
        byte[] bytes = siteConfigService.readLogo();
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        SysSiteConfig config = siteConfigService.get();
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(config.getLogo()))
                .header("Cache-Control", "no-cache")
                .body(bytes);
    }

    /** 更新站点配置（仅超级管理员） */
    @PutMapping("/config")
    public R<Map<String, Object>> update(@RequestBody SiteConfigRequest req) {
        requireSuperAdmin();
        SysSiteConfig config = siteConfigService.update(req, LoginHelper.getUserId());
        return R.ok(toAdminVo(config));
    }

    /** 上传站点图标（仅超级管理员） */
    @PostMapping("/favicon")
    public R<Map<String, Object>> uploadFavicon(@RequestParam("file") MultipartFile file) throws IOException {
        requireSuperAdmin();
        if (file == null || file.isEmpty()) {
            return R.fail("请选择图标文件");
        }
        SysSiteConfig config = siteConfigService.saveFavicon(file.getOriginalFilename(), file.getInputStream());
        return R.ok(toAdminVo(config));
    }

    /** 上传站点标识图（仅超级管理员） */
    @PostMapping("/logo")
    public R<Map<String, Object>> uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
        requireSuperAdmin();
        if (file == null || file.isEmpty()) {
            return R.fail("请选择标识图文件");
        }
        SysSiteConfig config = siteConfigService.saveLogo(file.getOriginalFilename(), file.getInputStream());
        return R.ok(toAdminVo(config));
    }

    private void requireSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            throw new ServiceException("仅超级管理员可修改站点配置");
        }
    }

    /**
     * 公开视图：登录页需要站点名称/备案/版权/图标，以及「注册」「忘记密码」入口是否可用。
     *
     * <p>绝不包含 SMTP 主机、账号、密码等敏感信息。
     */
    private Map<String, Object> toPublicVo(SysSiteConfig config) {
        Map<String, Object> vo = baseVo(config);
        vo.put("registerEnabled", Boolean.TRUE.equals(config.getRegisterEnabled()));
        // 只有「找回密码开关打开」且「邮件配置可用」时，前端才展示忘记密码入口
        vo.put("resetEnabled", Boolean.TRUE.equals(config.getResetEnabled()));
        vo.put("mailReady", siteMailService.isReady(config));
        vo.put("passwordResetAvailable",
                Boolean.TRUE.equals(config.getResetEnabled()) && siteMailService.isReady(config));
        return vo;
    }

    /** 管理视图：公开字段 + 注册/找回/邮件配置（邮件密码仅返回是否已设置） */
    private Map<String, Object> toAdminVo(SysSiteConfig config) {
        Map<String, Object> vo = baseVo(config);
        vo.put("registerEnabled", Boolean.TRUE.equals(config.getRegisterEnabled()));
        vo.put("registerRoleIds", config.getRegisterRoleIds() == null ? "" : config.getRegisterRoleIds());
        vo.put("resetEnabled", Boolean.TRUE.equals(config.getResetEnabled()));
        vo.put("mailEnabled", Boolean.TRUE.equals(config.getMailEnabled()));
        vo.put("mailHost", nullToEmpty(config.getMailHost()));
        vo.put("mailPort", config.getMailPort() == null ? 465 : config.getMailPort());
        vo.put("mailEncrypt", config.getMailEncrypt() == null ? "ssl" : config.getMailEncrypt());
        vo.put("mailUsername", nullToEmpty(config.getMailUsername()));
        vo.put("mailFrom", nullToEmpty(config.getMailFrom()));
        vo.put("mailFromName", nullToEmpty(config.getMailFromName()));
        // 出于安全不回传明文密码，只告知「已设置」，前端保存时空着表示不修改
        vo.put("mailPasswordSet", config.getMailPassword() != null && !config.getMailPassword().isBlank());
        vo.put("mailReady", siteMailService.isReady(config));
        vo.put("passwordResetAvailable",
                Boolean.TRUE.equals(config.getResetEnabled()) && siteMailService.isReady(config));
        return vo;
    }

    /** 公开与管理视图共用的基础字段 */
    private Map<String, Object> baseVo(SysSiteConfig config) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("siteName", config.getSiteName());
        vo.put("icp", nullToEmpty(config.getIcp()));
        vo.put("copyright", nullToEmpty(config.getCopyright()));
        vo.put("favicon", config.getFavicon());
        vo.put("logo", config.getLogo());
        // 带时间戳避免浏览器缓存旧图片
        long version = config.getUpdateTime() == null ? 0L
                : config.getUpdateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        vo.put("faviconUrl", config.getFavicon() == null || config.getFavicon().isBlank()
                ? "" : "/api/site/favicon?v=" + version);
        vo.put("logoUrl", config.getLogo() == null || config.getLogo().isBlank()
                ? "" : "/api/site/logo?v=" + version);
        return vo;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private MediaType mediaTypeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (name.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (name.endsWith(".webp")) return MediaType.valueOf("image/webp");
        return MediaType.valueOf("image/x-icon");
    }
}

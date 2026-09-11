package org.dromara.dms.doc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.SysSiteConfig;
import org.dromara.dms.doc.dto.SiteConfigRequest;
import org.dromara.dms.doc.service.SiteConfigService;
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

    /** 站点配置（公开：登录页需要） */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return R.ok(toVo(siteConfigService.get()));
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

    /** 更新站点配置（仅超级管理员） */
    @PutMapping("/config")
    public R<Map<String, Object>> update(@RequestBody SiteConfigRequest req) {
        requireSuperAdmin();
        SysSiteConfig config = siteConfigService.update(
                req.getSiteName(), req.getIcp(), req.getCopyright(), LoginHelper.getUserId());
        return R.ok(toVo(config));
    }

    /** 上传站点图标（仅超级管理员） */
    @PostMapping("/favicon")
    public R<Map<String, Object>> uploadFavicon(@RequestParam("file") MultipartFile file) throws IOException {
        requireSuperAdmin();
        if (file == null || file.isEmpty()) {
            return R.fail("请选择图标文件");
        }
        SysSiteConfig config = siteConfigService.saveFavicon(file.getOriginalFilename(), file.getInputStream());
        return R.ok(toVo(config));
    }

    private void requireSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            throw new ServiceException("仅超级管理员可修改站点配置");
        }
    }

    private Map<String, Object> toVo(SysSiteConfig config) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("siteName", config.getSiteName());
        vo.put("icp", config.getIcp() == null ? "" : config.getIcp());
        vo.put("copyright", config.getCopyright() == null ? "" : config.getCopyright());
        vo.put("favicon", config.getFavicon());
        // 带时间戳避免浏览器缓存旧图标
        long version = config.getUpdateTime() == null ? 0L
                : config.getUpdateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        vo.put("faviconUrl", config.getFavicon() == null || config.getFavicon().isBlank()
                ? "" : "/api/site/favicon?v=" + version);
        return vo;
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

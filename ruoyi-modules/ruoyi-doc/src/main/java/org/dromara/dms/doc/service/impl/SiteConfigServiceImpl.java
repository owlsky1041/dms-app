package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.SysSiteConfig;
import org.dromara.dms.doc.mapper.SysSiteConfigMapper;
import org.dromara.dms.doc.service.SiteConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 站点配置服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    /** 单行配置固定主键 */
    private static final long ROW_ID = 1L;

    /** 允许的图标扩展名 */
    private static final java.util.Set<String> ALLOWED_ICON_EXT =
            java.util.Set.of("ico", "png", "svg", "jpg", "jpeg", "gif", "webp");

    private final SysSiteConfigMapper siteConfigMapper;

    /** 站点资源目录（图标等），nginx 或后端直接读取 */
    @Value("${dms.site.asset-dir:/opt/dms/site-assets}")
    private String assetDir;

    @Override
    public SysSiteConfig get() {
        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        if (config == null) {
            // 兜底：表存在但行被误删时，返回默认值而不是抛错
            config = new SysSiteConfig().setId(ROW_ID).setSiteName("DMS 文档管理");
        }
        return config;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysSiteConfig update(String siteName, String icp, String copyright, Long operatorId) {
        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        if (config == null) {
            config = new SysSiteConfig().setId(ROW_ID);
        }
        if (siteName != null && !siteName.isBlank()) {
            config.setSiteName(siteName.trim());
        }
        config.setIcp(icp == null ? "" : icp.trim());
        config.setCopyright(copyright == null ? "" : copyright.trim());
        config.setUpdateBy(operatorId);
        config.setUpdateTime(LocalDateTime.now());
        if (siteConfigMapper.selectById(ROW_ID) == null) {
            siteConfigMapper.insert(config);
        } else {
            siteConfigMapper.updateById(config);
        }
        log.info("站点配置已更新: siteName={}, by={}", config.getSiteName(), operatorId);
        return config;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysSiteConfig saveFavicon(String originalFilename, InputStream in) {
        String ext = extractExt(originalFilename);
        if (!ALLOWED_ICON_EXT.contains(ext)) {
            // 用 ServiceException，否则会被全局异常处理器吞成「未知异常」
            throw new ServiceException("图标格式不支持，请上传 ico / png / svg / jpg / gif / webp");
        }
        String fileName = "favicon." + ext;
        try {
            Path dir = Paths.get(assetDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            // 清理旧的其它扩展名图标，避免歧义
            try (var stream = Files.list(dir)) {
                stream.filter(p -> !p.getFileName().toString().equals(fileName))
                        .filter(p -> p.getFileName().toString().startsWith("favicon."))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // 删不掉不影响使用
                            }
                        });
            }
            log.info("站点图标已保存: {}", target);
        } catch (IOException e) {
            throw new ServiceException("保存站点图标失败: " + e.getMessage());
        }

        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        if (config == null) {
            config = new SysSiteConfig().setId(ROW_ID).setSiteName("DMS 文档管理");
            config.setFavicon(fileName);
            config.setUpdateBy(LoginHelper.getUserId());
            config.setUpdateTime(LocalDateTime.now());
            siteConfigMapper.insert(config);
        } else {
            config.setFavicon(fileName);
            config.setUpdateBy(LoginHelper.getUserId());
            config.setUpdateTime(LocalDateTime.now());
            siteConfigMapper.updateById(config);
        }
        return config;
    }

    @Override
    public byte[] readFavicon() {
        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        if (config == null || config.getFavicon() == null || config.getFavicon().isBlank()) {
            return null;
        }
        Path path = Paths.get(assetDir).resolve(config.getFavicon());
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("读取站点图标失败: {}", path, e);
            return null;
        }
    }

    private String extractExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}

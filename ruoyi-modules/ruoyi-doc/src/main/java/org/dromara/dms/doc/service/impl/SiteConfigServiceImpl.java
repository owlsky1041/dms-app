package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.lang.Validator;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.SysSiteConfig;
import org.dromara.dms.doc.dto.SiteConfigRequest;
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
            config.setRegisterEnabled(false);
            config.setResetEnabled(false);
            config.setMailEnabled(false);
            config.setMailEncrypt("ssl");
            config.setMailPort(465);
        }
        // 历史行可能为 null，统一成明确默认值，避免前端拿到 null 需要各自兜底
        if (config.getRegisterEnabled() == null) config.setRegisterEnabled(false);
        if (config.getResetEnabled() == null) config.setResetEnabled(false);
        if (config.getMailEnabled() == null) config.setMailEnabled(false);
        if (config.getMailEncrypt() == null || config.getMailEncrypt().isBlank()) config.setMailEncrypt("ssl");
        if (config.getMailPort() == null) config.setMailPort(465);
        return config;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysSiteConfig update(SiteConfigRequest req, Long operatorId) {
        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        if (config == null) {
            config = new SysSiteConfig().setId(ROW_ID).setSiteName("DMS 文档管理");
        }
        // 站点基础信息：文本项为 null 时不修改
        if (req.getSiteName() != null && !req.getSiteName().isBlank()) {
            config.setSiteName(req.getSiteName().trim());
        }
        if (req.getIcp() != null) {
            config.setIcp(req.getIcp().trim());
        }
        if (req.getCopyright() != null) {
            config.setCopyright(req.getCopyright().trim());
        }
        // 注册 / 找回密码开关
        if (req.getRegisterEnabled() != null) {
            config.setRegisterEnabled(req.getRegisterEnabled());
        }
        if (req.getResetEnabled() != null) {
            config.setResetEnabled(req.getResetEnabled());
        }
        if (req.getRegisterRoleIds() != null) {
            config.setRegisterRoleIds(normalizeRoleIds(req.getRegisterRoleIds()));
        }
        // 邮件配置
        if (req.getMailEnabled() != null) {
            config.setMailEnabled(req.getMailEnabled());
        }
        if (req.getMailHost() != null) {
            config.setMailHost(req.getMailHost().trim());
        }
        if (req.getMailPort() != null) {
            config.setMailPort(req.getMailPort());
        }
        if (req.getMailEncrypt() != null) {
            config.setMailEncrypt(normalizeEncrypt(req.getMailEncrypt()));
        }
        if (req.getMailUsername() != null) {
            config.setMailUsername(req.getMailUsername().trim());
        }
        if (req.getMailPassword() != null && !req.getMailPassword().isEmpty()) {
            // 空字符串=前端未改动密码，保持原值；非空则覆盖
            config.setMailPassword(req.getMailPassword());
        }
        if (req.getMailFrom() != null) {
            config.setMailFrom(req.getMailFrom().trim());
        }
        if (req.getMailFromName() != null) {
            config.setMailFromName(req.getMailFromName().trim());
        }
        // 启用邮件前做一次必填校验，避免配错了却以为已生效
        if (Boolean.TRUE.equals(config.getMailEnabled())) {
            validateMailConfig(config);
        }
        config.setUpdateBy(operatorId);
        config.setUpdateTime(LocalDateTime.now());
        if (siteConfigMapper.selectById(ROW_ID) == null) {
            siteConfigMapper.insert(config);
        } else {
            siteConfigMapper.updateById(config);
        }
        log.info("站点配置已更新: siteName={}, registerEnabled={}, resetEnabled={}, mailEnabled={}, by={}",
                config.getSiteName(), config.getRegisterEnabled(), config.getResetEnabled(),
                config.getMailEnabled(), operatorId);
        return config;
    }

    /** 角色 ID 列表规范化：去空白、去重、以逗号连接；非法内容直接拒绝 */
    private String normalizeRoleIds(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String part : trimmed.split("[,，;；\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!part.chars().allMatch(Character::isDigit)) {
                throw new ServiceException("默认角色 ID 只能是数字，请用逗号分隔：" + part);
            }
            ids.add(part);
        }
        return String.join(",", ids);
    }

    /** 加密方式规范化，非法值回落到 ssl */
    private String normalizeEncrypt(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "ssl", "starttls", "none" -> value;
            case "" -> "ssl";
            default -> throw new ServiceException("邮件加密方式只能是 ssl / starttls / none");
        };
    }

    /** 邮件配置必填校验（仅在启用邮件时执行） */
    private void validateMailConfig(SysSiteConfig config) {
        if (isBlank(config.getMailHost())) {
            throw new ServiceException("启用邮件发送前请先填写 SMTP 服务器地址");
        }
        if (config.getMailPort() == null || config.getMailPort() <= 0 || config.getMailPort() > 65535) {
            throw new ServiceException("SMTP 端口不合法，应为 1-65535");
        }
        if (isBlank(config.getMailFrom())) {
            throw new ServiceException("启用邮件发送前请先填写发件邮箱地址");
        }
        // 提前校验格式，否则 JavaMail 只会抛出难以理解的
        // 「Local address contains control or whitespace」
        if (!Validator.isEmail(config.getMailFrom())) {
            throw new ServiceException("发件邮箱地址格式不正确：" + config.getMailFrom());
        }
        if (isBlank(config.getMailUsername())) {
            throw new ServiceException("启用邮件发送前请先填写 SMTP 登录账号");
        }
        if (!Validator.isEmail(config.getMailUsername())) {
            throw new ServiceException("SMTP 登录账号应为邮箱地址：" + config.getMailUsername());
        }
        if (isBlank(config.getMailPassword())) {
            throw new ServiceException("启用邮件发送前请先填写 SMTP 登录密码或授权码");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysSiteConfig saveFavicon(String originalFilename, InputStream in) {
        return saveBrandImage("favicon", "站点图标", originalFilename, in);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysSiteConfig saveLogo(String originalFilename, InputStream in) {
        return saveBrandImage("logo", "站点标识图", originalFilename, in);
    }

    /**
     * 保存品牌图片（favicon / logo 共用一套流程）
     *
     * <p>两者只差文件名前缀与落到哪个字段，逻辑完全一样：校验扩展名 → 写入
     * {@code asset-dir} → 清掉旧扩展名的同名文件（避免 favicon.png 与 favicon.svg
     * 同时存在、读的时候不知道读哪个）→ 回写库。
     *
     * @param kind 文件名前缀，{@code favicon} 或 {@code logo}
     * @param label 出错提示里用的中文名
     */
    private SysSiteConfig saveBrandImage(String kind, String label,
                                         String originalFilename, InputStream in) {
        String ext = extractExt(originalFilename);
        if (!ALLOWED_ICON_EXT.contains(ext)) {
            // 用 ServiceException，否则会被全局异常处理器吞成「未知异常」
            throw new ServiceException(label + "格式不支持，请上传 ico / png / svg / jpg / gif / webp");
        }
        String fileName = kind + "." + ext;
        try {
            Path dir = Paths.get(assetDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            // 清理旧的其它扩展名的同名文件，避免歧义
            try (var stream = Files.list(dir)) {
                stream.filter(p -> !p.getFileName().toString().equals(fileName))
                        .filter(p -> p.getFileName().toString().startsWith(kind + "."))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // 删不掉不影响使用
                            }
                        });
            }
            log.info("{}已保存: {}", label, target);
        } catch (IOException e) {
            throw new ServiceException("保存" + label + "失败：" + e.getMessage());
        }

        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        if (config == null) {
            config = new SysSiteConfig().setId(ROW_ID).setSiteName("DMS 文档管理");
            applyBrandImage(config, kind, fileName);
            config.setUpdateBy(LoginHelper.getUserId());
            config.setUpdateTime(LocalDateTime.now());
            siteConfigMapper.insert(config);
        } else {
            applyBrandImage(config, kind, fileName);
            config.setUpdateBy(LoginHelper.getUserId());
            config.setUpdateTime(LocalDateTime.now());
            siteConfigMapper.updateById(config);
        }
        return config;
    }

    @Override
    public byte[] readFavicon() {
        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        return readBrandImage(config == null ? null : config.getFavicon());
    }

    @Override
    public byte[] readLogo() {
        SysSiteConfig config = siteConfigMapper.selectById(ROW_ID);
        return readBrandImage(config == null ? null : config.getLogo());
    }

    /** 把文件名写到对应字段上（favicon 或 logo） */
    private void applyBrandImage(SysSiteConfig config, String kind, String fileName) {
        if ("logo".equals(kind)) {
            config.setLogo(fileName);
        } else {
            config.setFavicon(fileName);
        }
    }

    /** 从 asset-dir 读回图片字节（文件名为空或文件不存在时返回 null） */
    private byte[] readBrandImage(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Path path = Paths.get(assetDir).resolve(fileName);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("读取站点图片失败: {}", path, e);
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

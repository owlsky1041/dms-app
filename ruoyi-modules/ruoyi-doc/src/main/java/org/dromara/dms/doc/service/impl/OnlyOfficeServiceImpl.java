package org.dromara.dms.doc.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.OnlyOfficeService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.system.api.model.LoginUser;
import org.dromara.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OnlyOffice 集成实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlyOfficeServiceImpl implements OnlyOfficeService {

    /** 文档类型映射 */
    private static final Set<String> WORD_EXT = Set.of("doc", "docx", "odt", "rtf", "txt");
    private static final Set<String> CELL_EXT = Set.of("xls", "xlsx", "ods", "csv");
    private static final Set<String> SLIDE_EXT = Set.of("ppt", "pptx", "odp");

    /** 临时下载令牌有效期（毫秒）：足够 OnlyOffice 取到文档 */
    private static final long TOKEN_TTL_MS = 30 * 60 * 1000L;

    private final ISysConfigService configService;
    private final PermissionService permissionService;

    /** OnlyOffice 文档服务地址（浏览器可访问） */
    @Value("${dms.onlyoffice.url:http://127.0.0.1:8081}")
    private String onlyOfficeUrl;

    /**
     * OnlyOffice 服务端取文档时使用的本站地址。
     * 留空则与浏览器一致；容器内访问宿主机建议显式配置。
     */
    @Value("${dms.onlyoffice.document-base-url:}")
    private String documentBaseUrl;

    /** 令牌签名密钥 */
    @Value("${dms.onlyoffice.secret:dms-onlyoffice-secret}")
    private String secret;

    @Override
    public boolean supports(String fileExtension) {
        return fileExtension != null && SUPPORTED_EXT.contains(fileExtension.toLowerCase(Locale.ROOT));
    }

    @Override
    public Map<String, Object> buildEditorConfig(DocFile file, Long userId) {
        String ext = file.getFileExtension() == null ? "" : file.getFileExtension().toLowerCase(Locale.ROOT);
        String documentType = WORD_EXT.contains(ext) ? "word"
                : CELL_EXT.contains(ext) ? "cell"
                : SLIDE_EXT.contains(ext) ? "slide"
                : "pdf";

        String token = issueToken(file.getFileId(), userId);
        String base = StringUtils.isBlank(documentBaseUrl) ? onlyOfficeUrl : documentBaseUrl;
        // 注意：这里必须是 OnlyOffice 服务端能访问到的地址（不是浏览器地址栏的语义）
        String fileUrl = resolveDocumentBase() + "/api/onlyoffice/file/" + token;

        LoginUser loginUser = org.dromara.common.satoken.utils.LoginHelper.getLoginUser();

        Map<String, Object> document = new HashMap<>();
        document.put("fileType", ext);
        document.put("key", buildDocumentKey(file));
        document.put("title", file.getFileName());
        document.put("url", fileUrl);
        document.put("permissions", Map.of("edit", false, "download", true, "print", true));

        Map<String, Object> user = new HashMap<>();
        user.put("id", String.valueOf(userId));
        user.put("name", loginUser != null && loginUser.getNickname() != null
                ? loginUser.getNickname() : String.valueOf(userId));

        Map<String, Object> customization = new HashMap<>();
        customization.put("autosave", false);
        customization.put("forcesave", false);
        customization.put("compactHeader", true);
        customization.put("hideRightMenu", true);
        customization.put("toolbarNoTabs", false);
        // 原生水印（部分版本支持；前端另有浮层水印，二者不冲突时以浮层为准）
        Map<String, Object> wm = watermarkConfig(loginUser);
        boolean watermarkEnabled = Boolean.TRUE.equals(wm.get("enabled"));
        if (watermarkEnabled) {
            Map<String, Object> nativeWm = new HashMap<>();
            nativeWm.put("text", wm.get("text"));
            customization.put("watermark", nativeWm);
        }

        Map<String, Object> editorConfig = new HashMap<>();
        editorConfig.put("mode", "view");
        editorConfig.put("lang", "zh-CN");
        editorConfig.put("user", user);
        editorConfig.put("customization", customization);

        Map<String, Object> config = new HashMap<>();
        config.put("documentType", documentType);
        config.put("document", document);
        config.put("editorConfig", editorConfig);
        config.put("width", "100%");
        config.put("height", "100%");
        config.put("type", "desktop");

        Map<String, Object> result = new HashMap<>();
        result.put("dsUrl", onlyOfficeUrl);
        result.put("config", config);
        result.put("watermark", wm);
        // 前端用于「下载」按钮的判定（与原预览保持一致）
        result.put("canDownload", permissionService.hasFile(file.getFileId(), PermissionFlag.DOWNLOAD, userId));
        return result;
    }

    /**
     * OnlyOffice 服务端取文档用的本站基地址。
     * 未显式配置时，取 onlyOfficeUrl 的协议与主机并不合适（那是 DS 的地址），
     * 因此默认回退到 127.0.0.1（同机部署），生产建议显式配置 document-base-url。
     */
    private String resolveDocumentBase() {
        return StringUtils.isBlank(documentBaseUrl) ? "http://127.0.0.1" : documentBaseUrl;
    }

    /** 文档 key：OnlyOffice 用它做缓存标识，内容变化需换 key，这里用 fileId+更新时间 */
    private String buildDocumentKey(DocFile file) {
        long version = file.getUpdateTime() == null ? 0L
                : file.getUpdateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return "dms" + file.getFileId() + "v" + version;
    }

    // ================= 令牌 =================

    private String issueToken(Long fileId, Long userId) {
        long expireAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        String payload = fileId + "|" + userId + "|" + expireAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    @Override
    public Long verifyToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String encoded = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!sign(encoded).equals(sig)) {
            return null;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|");
            if (parts.length != 3) {
                return null;
            }
            if (Long.parseLong(parts[2]) < System.currentTimeMillis()) {
                return null;
            }
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    // ================= 水印 =================

    @Override
    public Map<String, Object> watermarkConfig(LoginUser user) {
        String enabled = configService.selectConfigByKey("sys.watermark.enabled");
        String template = configService.selectConfigByKey("sys.watermark.text");

        Map<String, Object> result = new HashMap<>();
        // 默认开启：需求即「预览加水印」，未配置时用真实姓名
        boolean on = StringUtils.isBlank(enabled) || "true".equalsIgnoreCase(enabled.trim());
        if (StringUtils.isBlank(template)) {
            template = "{realName}";
        }
        String realName = user != null && StringUtils.isNotBlank(user.getNickname())
                ? user.getNickname() : "";
        String account = user != null && StringUtils.isNotBlank(user.getUsername())
                ? user.getUsername() : "";

        String text = template
                .replace("{realName}", realName)
                .replace("{account}", account)
                .replace("{date}", LocalDate.now().toString())
                .replace("{time}", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        result.put("enabled", on && StringUtils.isNotBlank(text));
        result.put("text", text);
        return result;
    }
}

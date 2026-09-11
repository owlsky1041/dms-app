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

/**
 * OnlyOffice 集成实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlyOfficeServiceImpl implements OnlyOfficeService {

    /**
     * 内置基线：文档服务不可达时使用。
     * 与 OnlyOffice 9.4 的 /meta/formats 中「支持 view」的格式一致。
     */
    private static final Map<String, String> BASELINE_FORMATS = buildBaseline();

    private static Map<String, String> buildBaseline() {
        Map<String, String> m = new HashMap<>();
        // word
        for (String e : "doc,docm,docx,dot,dotm,dotx,epub,fb2,fodt,gdoc,hml,htm,html,hwp,hwpx,md,mht,mhtml,odt,ott,pages,rtf,stw,sxw,txt,wps,wpt,xml".split(",")) {
            m.put(e, "word");
        }
        // cell
        for (String e : "csv,et,ett,fods,gsheet,numbers,ods,ots,sxc,tsv,xls,xlsb,xlsm,xlsx,xlt,xltm,xltx".split(",")) {
            m.put(e, "cell");
        }
        // slide
        for (String e : "dps,dpt,fodp,gslides,key,odg,odp,otp,pot,potm,potx,pps,ppsm,ppsx,ppt,pptm,pptx,sxi".split(",")) {
            m.put(e, "slide");
        }
        // pdf
        for (String e : "djvu,docxf,oform,oxps,pdf,xps".split(",")) {
            m.put(e, "pdf");
        }
        // diagram（Visio）
        for (String e : "vsdm,vsdx,vssm,vssx,vstm,vstx".split(",")) {
            m.put(e, "diagram");
        }
        return Map.copyOf(m);
    }

    /** /meta/formats 的缓存与有效期 */
    private volatile Map<String, String> cachedFormats = BASELINE_FORMATS;
    private volatile long cachedAt = 0L;
    private static final long FORMAT_CACHE_MS = 60 * 60 * 1000L;

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

    /** 文档服务内部调用地址（后端 → 文档服务，用于读取支持格式等） */
    @Value("${dms.onlyoffice.internal-url:http://127.0.0.1:8081}")
    private String internalUrl;

    @Override
    public Map<String, String> supportedFormats() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < FORMAT_CACHE_MS) {
            return cachedFormats;
        }
        try {
            Map<String, String> fetched = fetchFormatsFromServer();
            if (!fetched.isEmpty()) {
                cachedFormats = Map.copyOf(fetched);
                cachedAt = now;
                return cachedFormats;
            }
        } catch (Exception e) {
            log.warn("获取文档服务支持格式失败，沿用上次结果: {}", e.getMessage());
        }
        cachedAt = now;   // 失败也记时间，避免每次请求都重试
        return cachedFormats;
    }

    /**
     * 调文档服务的 /meta/formats，取「支持 view 动作」的扩展名及其类型
     */
    private Map<String, String> fetchFormatsFromServer() throws Exception {
        String url = internalUrl.replaceAll("/$", "") + "/meta/formats";
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        String body = new org.springframework.web.client.RestTemplate(factory).getForObject(url, String.class);
        if (StringUtils.isBlank(body)) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        var nodes = cn.hutool.json.JSONUtil.parseArray(body);
        for (Object node : nodes) {
            var obj = (cn.hutool.json.JSONObject) node;
            String type = obj.getStr("type");
            String name = obj.getStr("name");
            if (StringUtils.isBlank(type) || StringUtils.isBlank(name)) {
                continue;
            }
            var actions = obj.getJSONArray("actions");
            if (actions == null || !actions.contains("view")) {
                continue;
            }
            result.put(name.toLowerCase(Locale.ROOT), type.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    @Override
    public boolean supports(String fileExtension) {
        return fileExtension != null
                && supportedFormats().containsKey(fileExtension.toLowerCase(Locale.ROOT));
    }

    @Override
    public Map<String, Object> buildEditorConfig(DocFile file, Long userId) {
        String ext = file.getFileExtension() == null ? "" : file.getFileExtension().toLowerCase(Locale.ROOT);
        String documentType = supportedFormats().getOrDefault(ext, "word");

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

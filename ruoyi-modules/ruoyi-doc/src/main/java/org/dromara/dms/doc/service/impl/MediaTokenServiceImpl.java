package org.dromara.dms.doc.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.dms.doc.service.MediaTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 媒体访问令牌实现（HMAC-SHA256 签名 + 过期时间）
 *
 * @author DMS
 */
@Slf4j
@Service
public class MediaTokenServiceImpl implements MediaTokenService {

    private static final String HMAC_ALG = "HmacSHA256";

    /** 令牌有效期：视频可能被暂停很久再继续播放，给宽一点；过期后前端重新取链接即可 */
    @Value("${dms.media.token-ttl-minutes:120}")
    private long ttlMinutes;

    /**
     * 签名密钥
     *
     * <p>留空则用进程内随机值：此时服务重启会让已发出的链接失效（媒体链接本来就是短期的，
     * 可接受），生产建议在配置里固定下来，避免重启后正在看的视频突然播不动。
     */
    @Value("${dms.media.token-secret:}")
    private String secret;

    private volatile byte[] key;

    private byte[] key() {
        byte[] k = key;
        if (k != null) {
            return k;
        }
        synchronized (this) {
            if (key == null) {
                String s = StringUtils.isBlank(secret)
                        ? Base64.getEncoder().encodeToString(randomBytes())
                        : secret;
                if (StringUtils.isBlank(secret)) {
                    log.warn("未配置 dms.media.token-secret，本次启动使用随机密钥：重启后旧的图片/视频链接会失效");
                }
                key = s.getBytes(StandardCharsets.UTF_8);
            }
            return key;
        }
    }

    private byte[] randomBytes() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    @Override
    public long ttlMillis() {
        return ttlMinutes * 60_000L;
    }

    @Override
    public String sign(Long fileId, Long userId, String kind) {
        // 载荷里放"过期时间 + 文件 + 用户 + 类型"：校验时不需要再查库就能挡掉过期与伪造
        long expireAt = System.currentTimeMillis() + ttlMillis();
        String payload = fileId + "|" + userId + "|" + kind + "|" + expireAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    @Override
    public Payload verify(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        String encoded = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        // 定长比较，避免按字符逐个比较带来的时序差异
        if (!MessageDigest.isEqual(sign(encoded).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|");
            if (parts.length != 4) {
                return null;
            }
            long expireAt = Long.parseLong(parts[3]);
            if (System.currentTimeMillis() > expireAt) {
                return null;
            }
            String kind = parts[2];
            if (!KIND_CONTENT.equals(kind) && !KIND_THUMB.equals(kind)) {
                return null;
            }
            return new Payload(Long.valueOf(parts[0]), Long.valueOf(parts[1]), kind);
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key(), HMAC_ALG));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("媒体令牌签名失败", e);
        }
    }
}

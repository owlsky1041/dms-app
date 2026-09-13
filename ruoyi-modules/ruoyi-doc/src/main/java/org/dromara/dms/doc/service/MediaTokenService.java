package org.dromara.dms.doc.service;

/**
 * 图片/视频等媒体内容的短期访问令牌
 *
 * <p>为什么需要它：图片用 {@code <img src>}、视频用 {@code <video src>} 直接加载，
 * 这类请求<b>带不上 Authorization 头</b>，而后端的预览接口要求登录态，
 * 于是浏览器拿到的是 401（被 RuoYi 包成 HTTP 200 + code 401 的 JSON），
 * el-image 只会显示"加载失败"、video 直接黑屏。
 *
 * <p>做法与 OnlyOffice 取文档一致：后端签发带签名的短期 URL，
 * 把该路径加入免登录白名单，由控制器校验令牌；<b>令牌本身不携带权限</b>，
 * 每次访问仍按 (fileId, userId) 重新校验预览权限，权限被收回后旧链接立即失效。
 *
 * @author DMS
 */
public interface MediaTokenService {

    /** 内容类型：原图 / 预览版（视频转码 mp4 与原件都走 content） */
    String KIND_CONTENT = "c";

    /** 缩略图 / 视频海报 */
    String KIND_THUMB = "t";

    /**
     * 签发令牌
     *
     * @param fileId 文件 ID
     * @param userId 签发对象（用于事后重新校验权限）
     * @param kind   {@link #KIND_CONTENT} 或 {@link #KIND_THUMB}
     * @return URL 安全的令牌串
     */
    String sign(Long fileId, Long userId, String kind);

    /**
     * 校验令牌
     *
     * @return 校验通过返回载荷，失败（签名不对 / 过期 / 格式错）返回 null
     */
    Payload verify(String token);

    /** 令牌有效期（毫秒），前端可用来决定何时重新取链接 */
    long ttlMillis();

    /**
     * 令牌载荷
     *
     * @param fileId 文件 ID
     * @param userId 签发时的用户
     * @param kind   内容类型
     */
    record Payload(Long fileId, Long userId, String kind) {
    }
}

package org.dromara.dms.doc.dto;

import lombok.Data;

/**
 * 站点配置更新请求
 *
 * <p>字段为 null 表示「不修改该项」，因此前端可以只提交部分字段。
 * {@code mailPassword} 传空字符串表示「保持原密码不变」——避免前端拿到掩码后
 * 又把掩码写回数据库。
 *
 * @author DMS
 */
@Data
public class SiteConfigRequest {

    /** 站点名称 */
    private String siteName;

    /** 备案信息 */
    private String icp;

    /** 版权信息 */
    private String copyright;

    /** 是否开放用户自助注册 */
    private Boolean registerEnabled;

    /** 自助注册用户默认角色 ID（逗号分隔，可传空字符串表示清空） */
    private String registerRoleIds;

    /** 是否开放邮箱验证码找回密码 */
    private Boolean resetEnabled;

    /** 是否启用邮件发送 */
    private Boolean mailEnabled;

    /** SMTP 服务器地址 */
    private String mailHost;

    /** SMTP 端口 */
    private Integer mailPort;

    /** SMTP 加密方式：ssl / starttls / none */
    private String mailEncrypt;

    /** SMTP 登录账号 */
    private String mailUsername;

    /** SMTP 登录密码或授权码；空字符串=保持原值不变 */
    private String mailPassword;

    /** 发件邮箱地址，例如 dms@company.com */
    private String mailFrom;

    /** 发件人显示名，例如「DMS 文档系统」 */
    private String mailFromName;
}

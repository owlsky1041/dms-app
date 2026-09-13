package org.dromara.dms.doc.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站点配置实体（单行表，id 固定为 1）
 *
 * <p>对应「系统管理 → 站点配置」：站点名称、登录页备案/版权、站点图标。
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("sys_site_config")
public class SysSiteConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 固定为 1 */
    @TableId(value = "id")
    private Long id;

    /** 站点名称：浏览器标题、页头、登录页 */
    private String siteName;

    /** 备案信息（登录页展示） */
    private String icp;

    /** 版权信息（登录页展示） */
    private String copyright;

    /** 站点图标文件名（存于站点资源目录） */
    private String favicon;

    /**
     * 站点标识图（登录页标题上方、主界面左上角那个图标）
     *
     * <p>与 favicon 分开存：favicon 是浏览器标签页那个 16×16 小图标，
     * 塞进登录页当大图会糊；这里存的是给人看的那张。
     */
    private String logo;

    // ==================== 用户自助注册 / 密码找回 ====================

    /** 是否开放用户自助注册 */
    private Boolean registerEnabled;

    /** 自助注册用户默认角色 ID（逗号分隔） */
    private String registerRoleIds;

    /** 是否开放邮箱验证码找回密码 */
    private Boolean resetEnabled;

    // ==================== 邮件（SMTP） ====================

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

    /** SMTP 登录密码或授权码 */
    private String mailPassword;

    /** 发件邮箱地址，例如 dms@company.com */
    private String mailFrom;

    /** 发件人显示名（拼 RFC-822 地址时用），留空则只显示邮箱 */
    private String mailFromName;

    private Long updateBy;

    private LocalDateTime updateTime;
}

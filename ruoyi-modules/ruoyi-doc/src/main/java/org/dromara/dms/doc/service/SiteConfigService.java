package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.SysSiteConfig;
import org.dromara.dms.doc.dto.SiteConfigRequest;

import java.io.InputStream;

/**
 * 站点配置服务
 *
 * @author DMS
 */
public interface SiteConfigService {

    /**
     * 读取站点配置（不存在时返回默认值，保证首次部署可用）
     */
    SysSiteConfig get();

    /**
     * 更新站点配置（站点名称 / 备案 / 版权 / 注册开关 / 邮件配置）
     *
     * <p>请求中为 null 的字段表示不修改；{@code mailPassword} 为空字符串表示保持原密码。
     */
    SysSiteConfig update(SiteConfigRequest req, Long operatorId);

    /**
     * 保存站点图标，返回新的配置
     *
     * @param originalFilename 原始文件名（用于推断扩展名）
     * @param in               图标内容
     */
    SysSiteConfig saveFavicon(String originalFilename, InputStream in);

    /** 保存站点标识图（登录页/主界面左上角用） */
    SysSiteConfig saveLogo(String originalFilename, InputStream in);

    /**
     * 读取站点图标字节；未配置时返回 null
     */
    byte[] readFavicon();

    /** 读取站点标识图（未配置返回 null） */
    byte[] readLogo();
}

package org.dromara.dms.doc.dto;

import lombok.Data;

/**
 * 站点配置更新请求
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
}

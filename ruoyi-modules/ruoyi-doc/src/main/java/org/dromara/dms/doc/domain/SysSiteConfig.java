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

    private Long updateBy;

    private LocalDateTime updateTime;
}

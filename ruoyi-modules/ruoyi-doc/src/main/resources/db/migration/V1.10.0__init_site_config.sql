-- ============================================================
-- 站点配置（单行配置表，id 固定为 1）
-- 用于「系统管理 → 站点配置」：站点名称、备案信息、版权信息、站点图标
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_site_config
(
    id          BIGINT       PRIMARY KEY,
    site_name   VARCHAR(100) NOT NULL DEFAULT 'DMS 文档管理',
    icp         VARCHAR(200),
    copyright   VARCHAR(200),
    favicon     VARCHAR(255),
    update_by   BIGINT,
    update_time TIMESTAMP
);

COMMENT ON TABLE sys_site_config IS '站点配置（单行，id=1）';
COMMENT ON COLUMN sys_site_config.site_name IS '站点名称（浏览器标题、页头、登录页）';
COMMENT ON COLUMN sys_site_config.icp IS '备案信息（登录页展示）';
COMMENT ON COLUMN sys_site_config.copyright IS '版权信息（登录页展示）';
COMMENT ON COLUMN sys_site_config.favicon IS '站点图标存储文件名';

INSERT INTO sys_site_config (id, site_name, icp, copyright, update_time)
VALUES (1, 'DMS 文档管理', '', '', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

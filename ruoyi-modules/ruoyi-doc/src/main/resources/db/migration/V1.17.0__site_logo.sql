-- ============================================================================
-- 站点标识图（logo）：登录页标题上方、主界面左上角那个图标，做成可配置
--
-- 为什么和 favicon 分开：
--   favicon 是浏览器标签页上的 16×16 小图标，拿去当登录页的大图会糊；
--   而登录页/主界面那个图标是给人看的，需要更清晰的一张。
--   两者各自上传、各自存储（同一目录下 favicon.* 与 logo.*），互不影响。
--
-- 幂等：列已存在则跳过。
-- ============================================================================

ALTER TABLE sys_site_config ADD COLUMN IF NOT EXISTS logo varchar(255);

COMMENT ON COLUMN sys_site_config.logo IS '站点标识图文件名（存于 dms.site.asset-dir），为空时前端用默认图标';

SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'sys_site_config' AND column_name IN ('favicon', 'logo')
ORDER BY column_name;

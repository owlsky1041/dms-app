-- ============================================================================
-- OnlyOffice 重要配置纳入「系统参数」
--
-- 背景：OnlyOffice 的几个关键参数（服务地址、回源地址、内部调用地址、令牌密钥）
-- 原先只写在 application.yml / application-dev.yml 里，改一次要改文件 + 重启服务。
-- 现在改为系统参数优先（代码里 cfg() 的顺序：sys_config → 配置文件 → 内置默认），
-- 页面上改完即时生效，不用重启。
--
-- 值按当前运行配置填好，保证行为与改造前一致（幂等：config_id 冲突就跳过）。
-- ============================================================================

-- OnlyOffice 重要配置纳入「系统参数」
-- 取值与配置文件一致（application.yml + /opt/dms/config/application-prod.yml），
-- 只是把入口从"改 yml 重启"搬到"页面上改、即时生效"。
INSERT INTO sys_config (config_id, config_key, config_value, config_name, config_type, create_by, create_time, remark)
VALUES
  (2200, 'dms.onlyoffice.enabled',        'true',                  'OnlyOffice-预览开关',            'Y', 1, now(), 'false=关闭在线预览（排障用），前端不再加载 OnlyOffice'),
  (2201, 'dms.onlyoffice.url',            '',                      'OnlyOffice-文档服务地址',        'Y', 1, now(), '浏览器访问 OnlyOffice 的地址；留空=与本站同源（由 nginx 反代到 127.0.0.1:8081，只能访问 80 端口时用这个）'),
  (2202, 'dms.onlyoffice.documentBaseUrl','',                      'OnlyOffice-文档回源地址',        'Y', 1, now(), 'OnlyOffice 服务端反过来拉取本站文档用的地址（容器场景填宿主机 IP）。留空=用配置文件 dms.onlyoffice.document-base-url；安装脚本会按本机地址生成，换服务器不用改这里'),
  (2203, 'dms.onlyoffice.internalUrl',    'http://127.0.0.1:8081', 'OnlyOffice-内部调用地址',        'Y', 1, now(), '后端 -> 文档服务，用于读取 /meta/formats 支持格式清单'),
  (2204, 'dms.onlyoffice.secret',         'dms-onlyoffice-2026',   'OnlyOffice-令牌签名密钥',        'Y', 1, now(), '与文档服务 local.json 里的 secret 必须一致，不一致会报"下载失败/令牌校验不通过"')
ON CONFLICT (config_id) DO NOTHING;

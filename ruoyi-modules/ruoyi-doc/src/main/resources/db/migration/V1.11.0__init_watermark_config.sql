-- 预览水印系统参数（可在「系统管理 → 系统参数」中修改）
-- 占位符：{realName} 真实姓名、{account} 登录账户、{date} 日期、{time} 日期时间
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES
  (2100, '预览水印开关', 'sys.watermark.enabled', 'true',  'Y', 103, 1, CURRENT_TIMESTAMP, 'true:开启 false:关闭（文档预览水印）'),
  (2101, '预览水印内容', 'sys.watermark.text',    '{realName}', 'Y', 103, 1, CURRENT_TIMESTAMP, '支持占位符 {realName} 真实姓名、{account} 账户、{date} 日期、{time} 日期时间')
ON CONFLICT (config_id) DO NOTHING;

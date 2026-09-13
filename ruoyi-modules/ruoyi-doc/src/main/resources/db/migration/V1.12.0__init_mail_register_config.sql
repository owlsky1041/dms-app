-- ============================================================
-- 站点配置扩展：用户自助注册 / 密码找回 / 邮件（SMTP）配置
-- 仍为单行表（id = 1），与 V1.10.0 的站点基础配置共用一行
-- ============================================================

ALTER TABLE sys_site_config
    -- 用户自助注册
    ADD COLUMN IF NOT EXISTS register_enabled  BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 注册后自动赋予的角色（逗号分隔的角色 ID，留空则仅创建账号不授角色）
    ADD COLUMN IF NOT EXISTS register_role_ids VARCHAR(255),
    -- 邮箱验证码找回密码
    ADD COLUMN IF NOT EXISTS reset_enabled     BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 邮件发送（SMTP）
    ADD COLUMN IF NOT EXISTS mail_enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS mail_host         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS mail_port         INTEGER              DEFAULT 465,
    -- 加密方式：ssl（SMTPS，默认 465）/ starttls（默认 587）/ none（明文，默认 25）
    ADD COLUMN IF NOT EXISTS mail_encrypt      VARCHAR(20)          DEFAULT 'ssl',
    ADD COLUMN IF NOT EXISTS mail_username     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS mail_password     VARCHAR(255),
    -- 发件邮箱地址；显示名单独存，避免把 `名称 <邮箱>` 这种 RFC-822 写法
    -- 直接放进请求体（RuoYi 的 XssFilter 会把尖括号内容当 HTML 标签剥掉）
    ADD COLUMN IF NOT EXISTS mail_from         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS mail_from_name    VARCHAR(100);

COMMENT ON COLUMN sys_site_config.register_enabled IS '是否开放用户自助注册';
COMMENT ON COLUMN sys_site_config.register_role_ids IS '自助注册用户默认角色 ID（逗号分隔）';
COMMENT ON COLUMN sys_site_config.reset_enabled IS '是否开放邮箱验证码找回密码';
COMMENT ON COLUMN sys_site_config.mail_enabled IS '是否启用邮件发送';
COMMENT ON COLUMN sys_site_config.mail_host IS 'SMTP 服务器地址';
COMMENT ON COLUMN sys_site_config.mail_port IS 'SMTP 端口';
COMMENT ON COLUMN sys_site_config.mail_encrypt IS 'SMTP 加密方式：ssl / starttls / none';
COMMENT ON COLUMN sys_site_config.mail_username IS 'SMTP 登录账号';
COMMENT ON COLUMN sys_site_config.mail_password IS 'SMTP 登录密码或授权码';
COMMENT ON COLUMN sys_site_config.mail_from IS '发件邮箱地址，例如 dms@company.com';
COMMENT ON COLUMN sys_site_config.mail_from_name IS '发件人显示名，例如「DMS 文档系统」，留空则只显示邮箱';

-- 已有行补齐默认值（新增列已有 DEFAULT，此处仅确保行存在）
INSERT INTO sys_site_config (id, site_name)
VALUES (1, 'DMS 文档管理')
ON CONFLICT (id) DO NOTHING;

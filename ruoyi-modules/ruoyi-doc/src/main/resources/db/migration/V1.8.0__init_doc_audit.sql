-- ============================================================
-- V1.8.0  活动日志表 doc_audit_log
-- ============================================================
-- 说明：
--   - 记录 doc 模块所有用户操作，便于审计和故障排查
--   - 保留 1 年（按 sys.recycle.retentionDays 配置可调）
--   - 与 sys_oper_log 互补：sys_oper_log 是 RuoYi 框架通用日志，doc_audit_log 是业务级日志
-- ============================================================

CREATE TABLE doc_audit_log (
    log_id         BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    action         VARCHAR(32)  NOT NULL,
    resource_type  VARCHAR(16),
    resource_id    BIGINT,
    resource_path  VARCHAR(500),
    ip             VARCHAR(64),
    user_agent     VARCHAR(500),
    detail         TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE doc_audit_log                IS 'doc 模块业务审计日志';
COMMENT ON COLUMN doc_audit_log.action        IS 'UPLOAD/DOWNLOAD/DELETE/MOVE/RENAME/PREVIEW/PERM_GRANT/PERM_REVOKE/SHARE/RESTORE';
COMMENT ON COLUMN doc_audit_log.resource_type IS 'FOLDER/FILE';
COMMENT ON COLUMN doc_audit_log.resource_id   IS '资源 ID';
COMMENT ON COLUMN doc_audit_log.resource_path IS '资源路径（如 /技术部/手册/）';
COMMENT ON COLUMN doc_audit_log.detail        IS 'JSON 详情';

CREATE INDEX idx_audit_user     ON doc_audit_log(user_id, created_at DESC);
CREATE INDEX idx_audit_resource ON doc_audit_log(resource_type, resource_id, created_at DESC);
CREATE INDEX idx_audit_action   ON doc_audit_log(action, created_at DESC);

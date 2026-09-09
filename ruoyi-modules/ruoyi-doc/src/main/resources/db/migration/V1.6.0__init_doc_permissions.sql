-- ============================================================
-- V1.6.0  权限表：文件夹权限 + 文件权限
-- ============================================================
-- 权限模型：
--   - 三种主体：user / role / dept
--   - 位掩码 8 位（perm_flags），见 PermissionFlag 枚举
--   - 文件夹支持 inherit_to_children（权限继承到子项）
--   - expires_at 支持授权过期
-- ============================================================

CREATE TABLE doc_folder_permission (
    perm_id              BIGSERIAL   PRIMARY KEY,
    folder_id            BIGINT      NOT NULL,
    subject_type         VARCHAR(20) NOT NULL,
    subject_id           BIGINT      NOT NULL,
    perm_flags           INT         NOT NULL,
    inherit_to_children  BOOLEAN     NOT NULL DEFAULT TRUE,
    granted_by           BIGINT      NOT NULL,
    granted_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMP,
    CONSTRAINT uk_folder_perm UNIQUE (folder_id, subject_type, subject_id)
);

COMMENT ON TABLE doc_folder_permission                   IS '文件夹权限表';
COMMENT ON COLUMN doc_folder_permission.perm_id         IS '主键';
COMMENT ON COLUMN doc_folder_permission.folder_id       IS '文件夹 ID';
COMMENT ON COLUMN doc_folder_permission.subject_type    IS '主体类型: user/role/dept';
COMMENT ON COLUMN doc_folder_permission.subject_id      IS '主体 ID';
COMMENT ON COLUMN doc_folder_permission.perm_flags      IS '位掩码权限值';
COMMENT ON COLUMN doc_folder_permission.inherit_to_children IS '是否继承到子文件夹和文件';
COMMENT ON COLUMN doc_folder_permission.granted_by      IS '授权人';
COMMENT ON COLUMN doc_folder_permission.expires_at      IS '过期时间（NULL=永久）';

CREATE INDEX idx_folder_perm_subject ON doc_folder_permission(subject_type, subject_id);
CREATE INDEX idx_folder_perm_folder  ON doc_folder_permission(folder_id);

-- ============================================================

CREATE TABLE doc_file_permission (
    perm_id         BIGSERIAL   PRIMARY KEY,
    file_id         BIGINT      NOT NULL,
    subject_type    VARCHAR(20) NOT NULL,
    subject_id      BIGINT      NOT NULL,
    perm_flags      INT         NOT NULL,
    granted_by      BIGINT      NOT NULL,
    granted_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP,
    CONSTRAINT uk_file_perm UNIQUE (file_id, subject_type, subject_id)
);

COMMENT ON TABLE doc_file_permission               IS '文件权限表（覆盖文件夹继承权限）';
COMMENT ON COLUMN doc_file_permission.perm_id     IS '主键';
COMMENT ON COLUMN doc_file_permission.file_id     IS '文件 ID';
COMMENT ON COLUMN doc_file_permission.subject_type IS '主体类型';
COMMENT ON COLUMN doc_file_permission.subject_id   IS '主体 ID';
COMMENT ON COLUMN doc_file_permission.perm_flags   IS '位掩码权限值';
COMMENT ON COLUMN doc_file_permission.expires_at   IS '过期时间';

CREATE INDEX idx_file_perm_subject ON doc_file_permission(subject_type, subject_id);
CREATE INDEX idx_file_perm_file    ON doc_file_permission(file_id);

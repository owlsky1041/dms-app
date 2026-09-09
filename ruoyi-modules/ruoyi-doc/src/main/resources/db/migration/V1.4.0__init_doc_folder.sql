-- ============================================================
-- V1.4.0  文件夹表 doc_folder
-- ============================================================
-- 表说明：
--   - folder_path 物化路径，'/0/5/12/' 形式便于前缀查询
--   - 软删除（deleted_at）实现回收站
--   - 同级目录下 folder_name + deleted_at 唯一，允许恢复后重名
-- ============================================================

CREATE TABLE doc_folder (
    folder_id      BIGSERIAL    PRIMARY KEY,
    parent_id      BIGINT       NOT NULL DEFAULT 0,
    folder_path    TEXT         NOT NULL,
    folder_name    VARCHAR(255) NOT NULL,
    icon           VARCHAR(50),
    description    VARCHAR(500),
    sort_order     INT          NOT NULL DEFAULT 0,
    owner_id       BIGINT       NOT NULL,
    dept_id        BIGINT,
    create_by      BIGINT,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by      BIGINT,
    update_time    TIMESTAMP,
    deleted_at     TIMESTAMP,
    CONSTRAINT uk_folder_path_name UNIQUE (folder_path, folder_name, deleted_at)
);

COMMENT ON TABLE  doc_folder               IS '文件夹表';
COMMENT ON COLUMN doc_folder.folder_id     IS '主键';
COMMENT ON COLUMN doc_folder.parent_id     IS '父文件夹 ID（0=根）';
COMMENT ON COLUMN doc_folder.folder_path   IS '物化路径 /0/5/12/';
COMMENT ON COLUMN doc_folder.folder_name   IS '显示名称';
COMMENT ON COLUMN doc_folder.owner_id      IS '创建者（自动拥有完全控制权）';
COMMENT ON COLUMN doc_folder.dept_id       IS '所属部门（数据权限用）';
COMMENT ON COLUMN doc_folder.deleted_at    IS '软删除时间（回收站）';

CREATE INDEX idx_folder_parent ON doc_folder(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_folder_path   ON doc_folder(folder_path) WHERE deleted_at IS NULL;
CREATE INDEX idx_folder_owner  ON doc_folder(owner_id);

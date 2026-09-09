-- ============================================================
-- V1.7.0  上传分块会话表 doc_upload_session（tus 协议会话持久化）
-- ============================================================
-- 说明：
--   - tus-java-server 自带磁盘存储分块，本表用于记录业务语义
--   - status: 0=上传中 1=完成 2=失败 3=取消 4=秒传
--   - file_hash 用于秒传校验
-- ============================================================

CREATE TABLE doc_upload_session (
    upload_id        VARCHAR(64) PRIMARY KEY,
    user_id          BIGINT      NOT NULL,
    folder_id        BIGINT,
    file_name        VARCHAR(255),
    file_hash        CHAR(64),
    file_size        BIGINT,
    chunk_size       INT,
    total_chunks     INT,
    uploaded_chunks  INT         NOT NULL DEFAULT 0,
    storage_key      VARCHAR(500),
    status           SMALLINT    NOT NULL DEFAULT 0,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     TIMESTAMP,
    CONSTRAINT uk_upload_user UNIQUE (upload_id, user_id)
);

COMMENT ON TABLE  doc_upload_session              IS '上传分块会话（tus 协议持久化）';
COMMENT ON COLUMN doc_upload_session.upload_id    IS 'tus 协议生成的 upload id';
COMMENT ON COLUMN doc_upload_session.user_id      IS '上传用户';
COMMENT ON COLUMN doc_upload_session.folder_id    IS '目标文件夹';
COMMENT ON COLUMN doc_upload_session.file_name    IS '逻辑文件名';
COMMENT ON COLUMN doc_upload_session.file_hash    IS '客户端计算的 SHA-256（秒传用）';
COMMENT ON COLUMN doc_upload_session.file_size    IS '原始文件大小';
COMMENT ON COLUMN doc_upload_session.uploaded_chunks IS '已上传分块数';
COMMENT ON COLUMN doc_upload_session.storage_key   IS 'tus 临时存储路径';
COMMENT ON COLUMN doc_upload_session.status        IS '0=上传中 1=完成 2=失败 3=取消 4=秒传';

CREATE INDEX idx_upload_user   ON doc_upload_session(user_id, created_at DESC);
CREATE INDEX idx_upload_status ON doc_upload_session(status, created_at);
CREATE INDEX idx_upload_hash   ON doc_upload_session(file_hash) WHERE status = 4;

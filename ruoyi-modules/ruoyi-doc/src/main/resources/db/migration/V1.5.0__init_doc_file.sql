-- ============================================================
-- V1.5.0  文件表 doc_file + 全文搜索
-- ============================================================
-- 设计要点：
--   - file_hash SHA-256 全局唯一（秒传、引用计数）
--   - storage_backend = 'minio'，预留扩展本地存储
--   - 文件名用 pg_trgm GIN 索引支持模糊搜索
--   - search_vector 是 GENERATED ALWAYS 列，自动维护
--   - 文件内容全文检索依赖 doc_file_text（异步填充）
-- ============================================================

-- 启用 pg_trgm 扩展（用于文件名模糊搜索）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE doc_file (
    file_id          BIGSERIAL    PRIMARY KEY,
    folder_id        BIGINT       NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_extension   VARCHAR(20),
    file_size        BIGINT       NOT NULL DEFAULT 0,
    file_hash        CHAR(64)     NOT NULL,
    mime_type        VARCHAR(100),
    storage_backend   VARCHAR(20)  NOT NULL DEFAULT 'minio',
    storage_bucket   VARCHAR(100),
    storage_key      VARCHAR(500),
    preview_key      VARCHAR(500),
    thumbnail_key    VARCHAR(500),
    page_count       INT          DEFAULT 0,
    width            INT,
    height           INT,
    duration_ms      BIGINT,
    creator_id       BIGINT       NOT NULL,
    dept_id          BIGINT,
    description      TEXT,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP,
    update_by        BIGINT,
    deleted_at       TIMESTAMP,
    CONSTRAINT uk_file_hash UNIQUE (file_hash, deleted_at)
);

COMMENT ON TABLE  doc_file                IS '文件元数据表';
COMMENT ON COLUMN doc_file.file_id        IS '主键';
COMMENT ON COLUMN doc_file.folder_id      IS '所属文件夹';
COMMENT ON COLUMN doc_file.file_name      IS '逻辑文件名';
COMMENT ON COLUMN doc_file.file_extension IS '扩展名（不含点）';
COMMENT ON COLUMN doc_file.file_size      IS '字节';
COMMENT ON COLUMN doc_file.file_hash      IS 'SHA-256（秒传键）';
COMMENT ON COLUMN doc_file.mime_type      IS '标准 MIME（Apache Tika 识别）';
COMMENT ON COLUMN doc_file.storage_backend IS '存储后端: minio / local';
COMMENT ON COLUMN doc_file.storage_bucket  IS 'MinIO 桶名';
COMMENT ON COLUMN doc_file.storage_key     IS 'MinIO 对象 key（原文件）';
COMMENT ON COLUMN doc_file.preview_key     IS '预览文件 key（PDF）';
COMMENT ON COLUMN doc_file.thumbnail_key   IS '缩略图 key';
COMMENT ON COLUMN doc_file.page_count      IS '页数（PDF/Office）';
COMMENT ON COLUMN doc_file.duration_ms     IS '音视频时长';
COMMENT ON COLUMN doc_file.creator_id      IS '上传者';
COMMENT ON COLUMN doc_file.dept_id         IS '所属部门';
COMMENT ON COLUMN doc_file.deleted_at      IS '软删除时间';

CREATE INDEX idx_file_folder   ON doc_file(folder_id)    WHERE deleted_at IS NULL;
CREATE INDEX idx_file_hash     ON doc_file(file_hash);
CREATE INDEX idx_file_create   ON doc_file(create_time DESC);
CREATE INDEX idx_file_creator  ON doc_file(creator_id, create_time DESC);
CREATE INDEX idx_file_name_trgm ON doc_file USING gin (file_name gin_trgm_ops);

-- ============================================================
-- 文件全文搜索（PostgreSQL tsvector，GENERATED ALWAYS 自动维护）
-- ============================================================
-- 包含三部分：
--   - file_name（文件名）
--   - description（描述）
--   - doc_file_text.extract_text（异步提取的正文）
-- ============================================================

CREATE TABLE doc_file_text (
    file_id      BIGINT    PRIMARY KEY,
    extract_text TEXT,
    extract_time TIMESTAMP,
    CONSTRAINT fk_file_text FOREIGN KEY (file_id) REFERENCES doc_file(file_id) ON DELETE CASCADE
);

COMMENT ON TABLE doc_file_text          IS '文件提取文本（异步填充）';
COMMENT ON COLUMN doc_file_text.extract_text IS '提取的纯文本';
COMMENT ON COLUMN doc_file_text.extract_time IS '提取时间';

-- 添加 search_vector 生成列（注意：extract_text 是子查询生成，需要 trigger 维护）
ALTER TABLE doc_file ADD COLUMN search_vector TSVECTOR;

-- 用 trigger 在 doc_file_text 更新时刷新 search_vector
CREATE OR REPLACE FUNCTION doc_file_refresh_search_vector()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE doc_file SET search_vector =
        setweight(to_tsvector('simple', coalesce(file_name, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(
            (SELECT extract_text FROM doc_file_text WHERE file_id = NEW.file_id), ''
        )), 'C')
    WHERE file_id = NEW.file_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_doc_file_text_refresh
AFTER INSERT OR UPDATE OR DELETE ON doc_file_text
FOR EACH ROW EXECUTE FUNCTION doc_file_refresh_search_vector();

-- file_name 或 description 变化时也需刷新
CREATE OR REPLACE FUNCTION doc_file_refresh_search_vector_self()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('simple', coalesce(NEW.file_name, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.description, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(
            (SELECT extract_text FROM doc_file_text WHERE file_id = NEW.file_id), ''
        )), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_doc_file_self_refresh
BEFORE INSERT OR UPDATE OF file_name, description ON doc_file
FOR EACH ROW EXECUTE FUNCTION doc_file_refresh_search_vector_self();

CREATE INDEX idx_file_search ON doc_file USING GIN(search_vector);

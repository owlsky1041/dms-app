-- ============================================================
-- V1.13.0  异步打包下载任务表 doc_export_task
-- ============================================================
-- 说明：
--   - 体量大（超过阈值）的文件夹打包改为后台异步执行：
--     立刻返回任务，后台生成 ZIP 落盘，完成后可在「导出任务」里下载
--   - 落盘而不是写 MinIO：ZIP 是一次性中间产物，写对象存储会污染桶并多一次 IO；
--     过期由定时任务清理（见 dms.download.zip.async-retention-hours）
--   - done_files / done_bytes 用于前端显示真实进度（同步流式模式拿不到百分比）
-- ============================================================

CREATE TABLE IF NOT EXISTS doc_export_task
(
    task_id            BIGINT       PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    root_name          VARCHAR(200) NOT NULL,
    resource_type      VARCHAR(16),
    resource_id        BIGINT,
    -- PENDING / RUNNING / SUCCESS / FAILED / CANCELED / EXPIRED
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    file_count         INTEGER      NOT NULL DEFAULT 0,
    total_bytes        BIGINT       NOT NULL DEFAULT 0,
    done_files         INTEGER      NOT NULL DEFAULT 0,
    done_bytes         BIGINT       NOT NULL DEFAULT 0,
    zip_bytes          BIGINT       NOT NULL DEFAULT 0,
    skipped_count      INTEGER      NOT NULL DEFAULT 0,
    file_path          VARCHAR(500),
    error_msg          TEXT,
    -- 打包计划（文件夹/文件 ID 列表）JSON，用于失败重试与排查
    plan_json          TEXT,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    start_time         TIMESTAMP,
    finish_time        TIMESTAMP,
    expire_time        TIMESTAMP,
    download_count     INTEGER      NOT NULL DEFAULT 0,
    last_download_time TIMESTAMP
);

COMMENT ON TABLE doc_export_task IS '异步打包下载任务';
COMMENT ON COLUMN doc_export_task.status IS 'PENDING/RUNNING/SUCCESS/FAILED/CANCELED/EXPIRED';
COMMENT ON COLUMN doc_export_task.done_files IS '已打包文件数（进度）';
COMMENT ON COLUMN doc_export_task.done_bytes IS '已打包字节数（进度）';
COMMENT ON COLUMN doc_export_task.file_path IS '生成的 ZIP 落盘路径';
COMMENT ON COLUMN doc_export_task.plan_json IS '打包计划 JSON（文件夹/文件 ID）';

CREATE INDEX IF NOT EXISTS idx_export_user   ON doc_export_task(user_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_export_status ON doc_export_task(status, create_time);
CREATE INDEX IF NOT EXISTS idx_export_expire ON doc_export_task(expire_time);

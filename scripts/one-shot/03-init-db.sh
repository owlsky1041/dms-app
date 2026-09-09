#!/bin/bash
#
# DMS 数据库初始化（Debian 13.5）
# ============================================
# 用途：导入 RuoYi 基础表（PostgreSQL 官方脚本）+ DMS doc 业务表
#
# 用法：sudo bash /opt/dms/03-init-db.sh
# （需先执行 01-init-debian13.sh 安装好 PostgreSQL）
#
# 前置条件：本文件旁边的 /opt/dms/sql/ 目录需包含：
#   postgres_ry_vue.sql      (RuoYi 基础表，来自 dms-app/script/sql/postgres/)
#   postgres_ry_job.sql      (RuoYi 定时任务表)
#   V1.4.0__init_doc_folder.sql ... V1.9.0__init_default_data.sql (DMS doc 表)
#
set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[$(date +'%H:%M:%S')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERR]${NC} $*"; }

if [ "$(id -u)" -ne 0 ]; then
    err "请用 root 执行：sudo bash $0"
    exit 1
fi

SQL_DIR=/opt/dms/sql
DB_NAME=dms
DB_USER=dms

# ============================================================
# 1. 确认 sql 文件齐全
# ============================================================
log "1. 检查 SQL 文件..."
FILES=(
    "postgres_ry_vue.sql"
    "postgres_ry_job.sql"
    "V1.4.0__init_doc_folder.sql"
    "V1.5.0__init_doc_file.sql"
    "V1.6.0__init_doc_permissions.sql"
    "V1.7.0__init_doc_upload.sql"
    "V1.8.0__init_doc_audit.sql"
    "V1.9.0__init_default_data.sql"
)
MISSING=0
for f in "${FILES[@]}"; do
    if [ ! -f "$SQL_DIR/$f" ]; then
        warn "缺少：$SQL_DIR/$f"
        MISSING=1
    fi
done
if [ "$MISSING" -eq 1 ]; then
    err "SQL 文件不齐，请先上传到 $SQL_DIR 再运行"
    exit 1
fi

# ============================================================
# 2. 等待 PostgreSQL 启动
# ============================================================
log "2. 等待 PostgreSQL..."
for i in 1 2 3 4 5 6 7 8 9 10; do
    if sudo -u postgres psql -c 'SELECT 1' >/dev/null 2>&1; then break; fi
    sleep 2
done

# ============================================================
# 3. 导入 RuoYi 基础表
# ============================================================
log "3. 导入 RuoYi 基础表（postgres_ry_vue.sql）..."
sudo -u postgres psql -d $DB_NAME -v ON_ERROR_STOP=1 -f $SQL_DIR/postgres_ry_vue.sql

log "4. 导入 RuoYi 任务表（postgres_ry_job.sql，可选）..."
sudo -u postgres psql -d $DB_NAME -v ON_ERROR_STOP=0 -f $SQL_DIR/postgres_ry_job.sql || warn "任务表导入失败（可忽略，DMS 用 @Scheduled 替代）"

# ============================================================
# 4. 导入 DMS doc 业务表（V1.4 ~ V1.9）
# ============================================================
log "5. 导入 DMS doc 表（V1.4 ~ V1.9）..."
for i in 4 5 6 7 8 9; do
    F=$(ls $SQL_DIR/V1.$i.0__*.sql 2>/dev/null | head -1)
    if [ -n "$F" ]; then
        log "  执行：$(basename $F)"
        sudo -u postgres psql -d $DB_NAME -v ON_ERROR_STOP=1 -f "$F"
    else
        warn "  V1.$i.0 脚本缺失，跳过"
    fi
done

# ============================================================
# 5. doc 表授权给 dms 用户
# ============================================================
log "6. 授权 dms 用户访问 doc 表..."
sudo -u postgres psql -d $DB_NAME <<'EOF'
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO dms;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO dms;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO dms;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO dms;
EOF

# ============================================================
# 6. 完成
# ============================================================
log "✅ 数据库初始化完成"
echo ""
echo "数据库连接：jdbc:postgresql://127.0.0.1:5432/dms"
echo "用户：dms / dms_password"
echo "默认账号：admin / admin123"

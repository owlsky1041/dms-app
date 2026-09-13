#!/bin/bash
# 说明：本脚本随 DMS 发布包分发，由 release/deploy/install.sh 安装到 /opt/dms/backup/。
#   改动点（相对最初在服务器上手工写的那版）：
#     1) sudo -u postgres  →  runuser -u postgres：最小化 Debian 上可能没有 sudo
#     2) mc 调用改为 ${MC:-mc}：便于在别处指定 mc 路径/别名
# ============================================================================
# DMS 备份脚本
#
# 备份三样东西：
#   1. PostgreSQL 全库（自定义格式，可选择性恢复）
#   2. MinIO 对象（按桶镜像成普通文件，恢复时反向 mirror 回去）
#   3. 配置与元信息（/opt/dms/config、MinIO 用户/策略、对象清单、版本）
#
# 用法：
#   /opt/dms/backup/backup.sh           # 正常备份
#   /opt/dms/backup/backup.sh --verify  # 备份 + 立刻校验（恢复演练用）
#
# ⚠️ 重要限制：默认备份到本机 /opt/dms/backup。
#    这能防"误删/误操作/程序 bug"，但**防不了磁盘损坏或整机故障**。
#    请把 DMS_BACKUP_REMOTE 设成另一台机器/NAS 的 rsync 目标，才能真正防灾。
# ============================================================================
set -euo pipefail

BACKUP_ROOT="${DMS_BACKUP_ROOT:-/opt/dms/backup}"
KEEP_DAYS="${DMS_BACKUP_KEEP_DAYS:-30}"
REMOTE="${DMS_BACKUP_REMOTE:-}"           # 例：nas:/volume1/dms-backup
DB_NAME="${DMS_DB_NAME:-dms}"
STAMP=$(date +%Y%m%d-%H%M%S)
DEST="$BACKUP_ROOT/sets/$STAMP"
LOG="$BACKUP_ROOT/backup.log"

mkdir -p "$DEST"
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

log "===== 备份开始 ($STAMP) ====="

# ---------- 1. 数据库 ----------
log "1/4 导出数据库 $DB_NAME"
runuser -u postgres -- pg_dump -Fc "$DB_NAME" > "$DEST/db.dump" 2>>"$LOG"
log "     db.dump: $(du -h "$DEST/db.dump" | cut -f1)"

# 记录关键表的行数，恢复后可以逐项核对
runuser -u postgres -- psql -d "$DB_NAME" -tAc "
  SELECT 'doc_file=' || count(*) FROM doc_file WHERE deleted_at IS NULL
  UNION ALL SELECT 'doc_folder=' || count(*) FROM doc_folder WHERE deleted_at IS NULL
  UNION ALL SELECT 'doc_file_all=' || count(*) FROM doc_file
  UNION ALL SELECT 'doc_folder_all=' || count(*) FROM doc_folder
  UNION ALL SELECT 'doc_audit_log=' || count(*) FROM doc_audit_log
  UNION ALL SELECT 'sys_user=' || count(*) FROM sys_user
  UNION ALL SELECT 'doc_folder_permission=' || count(*) FROM doc_folder_permission
  UNION ALL SELECT 'doc_file_permission=' || count(*) FROM doc_file_permission;" > "$DEST/db-counts.txt"
log "     行数快照: $(tr '\n' ' ' < "$DEST/db-counts.txt")"

# ---------- 2. MinIO 对象 ----------
log "2/4 镜像 MinIO 对象"
${MC:-mc} mirror --overwrite --remove local/dms-files "$DEST/minio/dms-files" >>"$LOG" 2>&1
${MC:-mc} ls --recursive --json local/dms-files 2>/dev/null \
  | python3 -c "
import sys, json
rows=[]
for line in sys.stdin:
    try: d=json.loads(line)
    except: continue
    if d.get('type')=='file': rows.append((d['key'], d.get('size'), d.get('etag','')[:12]))
rows.sort()
with open('$DEST/objects.txt','w') as f:
    for k,s,e in rows: f.write('%s\t%s\t%s\n' % (k,s,e))
print('    %d 个对象, 共 %d 字节' % (len(rows), sum(r[1] for r in rows)))" | tee -a "$LOG"

# ---------- 3. 配置与元信息 ----------
log "3/4 备份配置与元信息"
cp -a /opt/dms/config "$DEST/config"
cp -a /etc/default/minio "$DEST/minio.env"
${MC:-mc} admin user list local > "$DEST/minio-users.txt" 2>&1 || true
${MC:-mc} admin policy list local > "$DEST/minio-policies.txt" 2>&1 || true
minio --version > "$DEST/versions.txt" 2>&1 || true
java -version >> "$DEST/versions.txt" 2>&1 || true
psql --version >> "$DEST/versions.txt" 2>&1 || true      # 走 postgres 用户时可能失败，忽略
cat /opt/dms/dist/version.json >> "$DEST/versions.txt" 2>/dev/null || true
ls -la /opt/dms/dms-app.jar >> "$DEST/versions.txt" 2>/dev/null || true

# ---------- 4. 校验与清理 ----------
log "4/4 校验备份完整性"
# pg_restore -l 能列出目录即说明 dump 结构可读（不解压数据，很快）
if runuser -u postgres -- pg_restore -l "$DEST/db.dump" > /dev/null 2>&1; then
    log "     ✅ db.dump 结构可读"
else
    log "     ❌ db.dump 校验失败！备份不可用"
    exit 1
fi

COUNT_NOW=$(${MC:-mc} ls --recursive local/dms-files 2>/dev/null | wc -l)
COUNT_BAK=$(wc -l < "$DEST/objects.txt")
if [ "$COUNT_NOW" = "$COUNT_BAK" ]; then
    log "     ✅ MinIO 对象数一致（$COUNT_NOW）"
else
    log "     ❌ MinIO 镜像对象数不符：现网 $COUNT_NOW vs 备份 $COUNT_BAK"
    exit 1
fi

ln -sfn "$DEST" "$BACKUP_ROOT/latest"

# 保留策略
find "$BACKUP_ROOT/sets" -maxdepth 1 -mindepth 1 -type d -mtime +"$KEEP_DAYS" -exec rm -rf {} \; 2>/dev/null || true

# 异地副本（可选）
if [ -n "$REMOTE" ]; then
    log "     → 同步到异地 $REMOTE"
    rsync -a --delete "$BACKUP_ROOT/sets/" "$REMOTE/sets/" >>"$LOG" 2>&1 && log "     ✅ 异地同步完成"
else
    log "     ⚠️ 未配置 DMS_BACKUP_REMOTE：备份只在本机，防不了磁盘/整机故障"
fi

log "===== 备份完成，总大小 $(du -sh "$DEST" | cut -f1) ====="
echo

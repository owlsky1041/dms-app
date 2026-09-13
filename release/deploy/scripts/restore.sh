#!/bin/bash
# 说明：本脚本随 DMS 发布包分发，由 release/deploy/install.sh 安装到 /opt/dms/backup/。
#   改动点（相对最初在服务器上手工写的那版）：
#     1) sudo -u postgres  →  runuser -u postgres：最小化 Debian 上可能没有 sudo
#     2) mc 调用改为 ${MC:-mc}：便于在别处指定 mc 路径/别名
# ============================================================================
# DMS 恢复脚本（从备份还原数据库与对象存储）
#
# 用法：
#   restore.sh --list                      列出可用备份
#   restore.sh --drill [STAMP]             恢复演练：还原到临时库/临时桶，核对后自动清理
#   restore.sh --db STAMP                  只还原数据库（会覆盖现网库！）
#   restore.sh --minio STAMP               只还原对象（会覆盖现网桶！）
#   restore.sh --all STAMP                 数据库 + 对象一起还原
#
# ⚠️ --db / --minio / --all 都是破坏性操作，会先要求输入 yes 确认。
#    常规排障请先用 --drill 验证备份真的可用。
# ============================================================================
set -euo pipefail

BACKUP_ROOT="${DMS_BACKUP_ROOT:-/opt/dms/backup}"
DB_NAME="${DMS_DB_NAME:-dms}"
DRILL_DB="dms_restore_drill"
DRILL_BUCKET="dms-files-drill"
MODE="${1:-}"
STAMP="${2:-}"

log() { echo "[$(date '+%F %T')] $*"; }

resolve() {
    if [ -z "$STAMP" ]; then
        STAMP=$(basename "$(readlink -f "$BACKUP_ROOT/latest")")
        log "未指定备份编号，使用 latest: $STAMP"
    fi
    SET="$BACKUP_ROOT/sets/$STAMP"
    [ -d "$SET" ] || { echo "找不到备份: $SET"; exit 1; }
}

case "$MODE" in
  --list)
    echo "可用备份（$BACKUP_ROOT/sets）："
    for d in "$BACKUP_ROOT"/sets/*/; do
        [ -d "$d" ] || continue
        printf "  %-18s %8s  db=%s  对象=%s\n" \
            "$(basename "$d")" \
            "$(du -sh "$d" 2>/dev/null | cut -f1)" \
            "$([ -f "$d/db.dump" ] && echo 有 || echo 无)" \
            "$(wc -l < "$d/objects.txt" 2>/dev/null || echo 0)"
    done
    echo
    echo "备份日志尾部："
    tail -5 "$BACKUP_ROOT/backup.log" 2>/dev/null || true
    exit 0
    ;;

  --drill)
    # 恢复演练：不碰现网数据，证明"备份真的能还原"
    resolve
    log "===== 恢复演练，使用备份 $STAMP ====="

    log "1/4 还原数据库到临时库 $DRILL_DB"
    runuser -u postgres -- psql -q -c "DROP DATABASE IF EXISTS $DRILL_DB;" 
    runuser -u postgres -- psql -q -c "CREATE DATABASE $DRILL_DB;"
    runuser -u postgres -- pg_restore -d "$DRILL_DB" --no-owner --no-privileges "$SET/db.dump" 2>&1 | grep -v "^pg_restore: warning" || true

    log "2/4 核对行数"
    FAIL=0
    while IFS='=' read -r key expect; do
        [ -z "$key" ] && continue
        table="${key%_all}"
        case "$key" in
            *_all) sql="SELECT count(*) FROM $table;" ;;
            doc_file) sql="SELECT count(*) FROM doc_file WHERE deleted_at IS NULL;" ;;
            doc_folder) sql="SELECT count(*) FROM doc_folder WHERE deleted_at IS NULL;" ;;
            *) sql="SELECT count(*) FROM $key;" ;;
        esac
        actual=$(runuser -u postgres -- psql -tAc "$sql" -d "$DRILL_DB")
        if [ "$actual" = "$expect" ]; then
            printf "     ✅ %-24s %s\n" "$key" "$actual"
        else
            printf "     ❌ %-24s 备份=%s 还原后=%s\n" "$key" "$expect" "$actual"
            FAIL=1
        fi
    done < "$SET/db-counts.txt"

    log "3/4 抽查对象内容（对比备份目录里的文件字节数）"
    ${MC:-mc} mb --ignore-existing "local/$DRILL_BUCKET" >/dev/null 2>&1 || true
    ${MC:-mc} mirror --overwrite "$SET/minio/dms-files" "local/$DRILL_BUCKET" >>/dev/null 2>&1
    BAK_COUNT=$(wc -l < "$SET/objects.txt")
    DRILL_COUNT=$(${MC:-mc} ls --recursive "local/$DRILL_BUCKET" 2>/dev/null | wc -l)
    if [ "$BAK_COUNT" = "$DRILL_COUNT" ]; then
        printf "     ✅ 对象数一致 %s\n" "$DRILL_COUNT"
    else
        printf "     ❌ 对象数不符 备份=%s 临时桶=%s\n" "$BAK_COUNT" "$DRILL_COUNT"
        FAIL=1
    fi
    # 抽第一个对象比对大小
    FIRST_KEY=$(head -1 "$SET/objects.txt" | cut -f1)
    if [ -n "$FIRST_KEY" ]; then
        S1=$(awk -F'\t' -v k="$FIRST_KEY" '$1==k{print $2}' "$SET/objects.txt")
        S2=$(${MC:-mc} stat --json "local/$DRILL_BUCKET/$FIRST_KEY" 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin).get('size',''))" 2>/dev/null || echo "")
        if [ "$S1" = "$S2" ]; then
            printf "     ✅ 抽查对象 %s 大小一致 (%s)\n" "$(basename "$FIRST_KEY")" "$S2"
        else
            printf "     ❌ 抽查对象大小不符 备份=%s 还原=%s\n" "$S1" "$S2"
            FAIL=1
        fi
    fi

    log "4/4 清理演练产物"
    runuser -u postgres -- psql -q -c "DROP DATABASE IF EXISTS $DRILL_DB;"
    ${MC:-mc} rb --force "local/$DRILL_BUCKET" >/dev/null 2>&1 || true

    if [ "$FAIL" = "0" ]; then
        log "===== 恢复演练通过 ✅（备份确实可用）====="
    else
        log "===== 恢复演练失败 ❌ 备份不可用，请立即排查 ====="
        exit 1
    fi
    exit 0
    ;;

  --db|--minio|--all)
    resolve
    echo "⚠️  即将用备份 $STAMP 覆盖："
    echo "    数据库: $DB_NAME"
    [[ "$MODE" == "--minio" || "$MODE" == "--all" ]] && echo "    对象桶: dms-files"
    echo "    当前时间: $(date '+%F %T')"
    read -r -p "输入 yes 继续: " ans
    [ "$ans" = "yes" ] || { echo "已取消"; exit 0; }

    if [[ "$MODE" == "--db" || "$MODE" == "--all" ]]; then
        log "还原数据库（先备份当前库以求稳妥）"
        runuser -u postgres -- pg_dump -Fc "$DB_NAME" > "/tmp/pre-restore-$(date +%Y%m%d-%H%M%S).dump"
        log "  当前库已另存到 /tmp/pre-restore-*.dump"
        runuser -u postgres -- psql -q -c "DROP DATABASE $DB_NAME WITH (FORCE);"
        runuser -u postgres -- psql -q -c "CREATE DATABASE $DB_NAME OWNER dms;"
        runuser -u postgres -- pg_restore -d "$DB_NAME" --no-owner --no-privileges "$SET/db.dump"
        log "  ✅ 数据库还原完成"
    fi
    if [[ "$MODE" == "--minio" || "$MODE" == "--all" ]]; then
        log "还原对象（mirror 回去，--remove 会清掉备份里没有的对象）"
        ${MC:-mc} mirror --overwrite --remove "$SET/minio/dms-files" local/dms-files
        log "  ✅ 对象还原完成"
    fi
    log "请重启应用并做功能验证：systemctl restart dms-app"
    exit 0
    ;;

  *)
    sed -n '2,20p' "$0"
    exit 1
    ;;
esac

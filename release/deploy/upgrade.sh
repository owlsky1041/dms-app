#!/usr/bin/env bash
# =============================================================================
# DMS 升级脚本（在已部署的服务器上运行）
#
# 做什么：备份 → 替换 jar 与前端 → 应用新迁移 → 重启 → 自检；
#         自检不通过自动回滚到升级前的版本。
#
# 用法：
#   ./upgrade.sh /path/to/dms-release-1.0.1        # 从解压后的新发布目录升级
#   ./upgrade.sh /path/to/dms-release-1.0.1 --no-rollback-check
#
# 说明：数据库迁移是**只增不改**的（历史迁移文件不动），因此回滚代码时不需要回滚库；
#       若某次升级确实改了数据结构，脚本会提示手工处理。
# =============================================================================
set -Eeuo pipefail

APP_DIR="/opt/dms"
CONF_DIR="${APP_DIR}/config"
LOG_DIR="${APP_DIR}/logs"
BACKUP_DIR="${APP_DIR}/backup"
NEW_DIR="${1:-}"
SKIP_VERIFY=0
[[ "${2:-}" == "--no-rollback-check" ]] && SKIP_VERIFY=1

[[ ${EUID} -eq 0 ]] || { echo "请用 root 运行" >&2; exit 1; }
[[ -n "${NEW_DIR}" && -d "${NEW_DIR}" ]] || { echo "用法：$0 <新发布目录>" >&2; exit 1; }
[[ -f "${NEW_DIR}/app/dms-app.jar" ]] || { echo "${NEW_DIR} 看起来不是发布包（缺 app/dms-app.jar）" >&2; exit 1; }

log() { echo "  · $*"; }
ok()  { echo "  ✔ $*"; }
die() { echo "  ✘ $*" >&2; exit 1; }

STAMP="$(date +%Y%m%d-%H%M%S)"
ROLLBACK_DIR="/opt/dms/rollback/${STAMP}"

echo "==== 1/7 升级前备份 ===="
install -d -m 0755 "${ROLLBACK_DIR}"
cp -a "${APP_DIR}/dms-app.jar" "${ROLLBACK_DIR}/dms-app.jar" 2>/dev/null || true
[[ -d "${APP_DIR}/dist" ]] && cp -a "${APP_DIR}/dist" "${ROLLBACK_DIR}/dist"
cp -a "${CONF_DIR}" "${ROLLBACK_DIR}/config"
if [[ -x "${BACKUP_DIR}/backup.sh" ]]; then
  log "执行一次完整备份（数据库+对象）"
  "${BACKUP_DIR}/backup.sh" | tail -3
fi
ok "已备份到 ${ROLLBACK_DIR}"

echo "==== 2/7 停应用 ===="
systemctl stop dms-app
ok "dms-app 已停止（前端静态资源仍在，页面会暂时不可用）"

rollback() {
  echo "  ! 升级失败，回滚代码与配置"
  cp -a "${ROLLBACK_DIR}/dms-app.jar" "${APP_DIR}/dms-app.jar"
  [[ -d "${ROLLBACK_DIR}/dist" ]] && { rm -rf "${APP_DIR}/dist"; cp -a "${ROLLBACK_DIR}/dist" "${APP_DIR}/dist"; }
  rm -rf "${CONF_DIR}"; cp -a "${ROLLBACK_DIR}/config" "${CONF_DIR}"
  systemctl start dms-app
  sleep 15
  systemctl is-active --quiet dms-app && echo "  ✔ 已回滚并启动（旧版本）" || echo "  ✘ 回滚后仍启动失败，请手工排查"
}

echo "==== 3/7 替换应用 ===="
install -m 0644 "${NEW_DIR}/app/dms-app.jar" "${APP_DIR}/dms-app.jar"
rm -rf "${APP_DIR}/dist.new"; cp -a "${NEW_DIR}/app/dist" "${APP_DIR}/dist.new"
rm -rf "${APP_DIR}/dist.old"; mv "${APP_DIR}/dist" "${APP_DIR}/dist.old" 2>/dev/null || true
mv "${APP_DIR}/dist.new" "${APP_DIR}/dist"
chown -R root:root "${APP_DIR}/dist"; chmod -R a+rX "${APP_DIR}/dist"
ok "jar 与前端已替换（旧前端保留在 ${APP_DIR}/dist.old）"

echo "==== 4/7 应用数据库迁移 ===="
# 复用安装脚本里的迁移逻辑：按 dms_schema_migrations 记录增量应用
export PGPASSWORD="$(grep ^DB_PASSWORD= /root/.dms-credentials | cut -d= -f2-)"
psql_cmd=(psql -h 127.0.0.1 -U dms -d dms -v ON_ERROR_STOP=1 -q)
applied_any=0
for f in $(ls "${NEW_DIR}"/sql/V*.sql 2>/dev/null | sort -V); do
  ver="$(basename "$f" .sql)"
  if [[ "$("${psql_cmd[@]}" -tAc "SELECT 1 FROM dms_schema_migrations WHERE version='${ver}'" || true)" == "1" ]]; then
    continue
  fi
  log "应用迁移 $ver"
  "${psql_cmd[@]}" -f "$f" || { rollback; die "迁移 $ver 执行失败"; }
  "${psql_cmd[@]}" -c "INSERT INTO dms_schema_migrations(version, note) VALUES ('${ver}','upgrade.sh') ON CONFLICT DO NOTHING"
  applied_any=1
done
[[ ${applied_any} -eq 0 ]] && log "没有新迁移需要应用" || ok "迁移已应用"
# SQL 目录也同步更新，便于后续排障
cp -a "${NEW_DIR}/sql/." "${APP_DIR}/sql/"

echo "==== 5/7 启动应用 ===="
systemctl start dms-app
up=0
for i in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8080/auth/login" 2>/dev/null || echo 000)
  [[ "$code" != "000" ]] && { up=1; break; }
  sleep 1
done
[[ ${up} -eq 1 ]] || { systemctl status dms-app --no-pager | tail -15; tail -30 "${LOG_DIR}/stderr.log" 2>/dev/null; rollback; die "新版启动失败"; }
ok "新版已启动"

echo "==== 6/7 升级后自检 ===="
if [[ ${SKIP_VERIFY} -eq 0 && -x "$(dirname "$0")/verify-deploy.sh" ]]; then
  if "$(dirname "$0")/verify-deploy.sh" | tail -12; then
    ok "自检通过"
  else
    rollback
    die "自检未通过，已回滚"
  fi
else
  log "按参数要求跳过自检"
fi

echo "==== 7/7 收尾 ===="
rm -rf "${APP_DIR}/dist.old"
ok "升级完成（版本信息见 ${NEW_DIR}/VERSION）"
echo
echo "  回滚方式：把 ${ROLLBACK_DIR} 里的内容拷回 ${APP_DIR} 后 systemctl restart dms-app"
echo "  注意：数据库迁移只增不改，回滚代码一般无需回滚库；如本次包含结构变更，脚本会在上面提示"

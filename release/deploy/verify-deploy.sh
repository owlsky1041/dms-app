#!/usr/bin/env bash
# =============================================================================
# 部署后自检 —— 装完马上跑一遍，确认"真的能用"，而不是只确认"服务起来了"
#
# 检查项：
#   1. 服务与端口：postgresql / redis / minio / dms-app / nginx
#   2. 数据库：能连、关键表在、菜单与角色数据在
#   3. 对象存储：桶在、应用账号能读写（真上传一个对象再删掉）
#   4. 应用接口：登录、取用户信息、列目录、权限位接口
#   5. 前端：首页 200 且引用的 JS 能取到
#   6. nginx：后端反代通、SPA 回落正常
#   7. 定时任务：备份 timer 已启用
#
# 用法：./verify-deploy.sh [--server-host 1.2.3.4]
# =============================================================================
set -uo pipefail

CRED_FILE="/root/.dms-credentials"
MINIO_CRED_FILE="/root/.minio-credentials"
APP_PORT=8080
SERVER_HOST=""
[[ "${1:-}" == "--server-host" ]] && SERVER_HOST="$2"
[[ -z "${SERVER_HOST}" ]] && SERVER_HOST="127.0.0.1"

PASS=0; FAIL=0
ok()   { echo "  ✔ $*"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $*"; FAIL=$((FAIL+1)); }
head_() { echo; echo "=== $* ==="; }

# shellcheck disable=SC1090
[[ -f "${CRED_FILE}" ]] && . "${CRED_FILE}"
DB_PASSWORD="${DB_PASSWORD:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
ADMIN_USER="${ADMIN_USER:-admin}"
CID="e5cd7e4891bf95d1d19206ce24a7b32e"
BASE="http://127.0.0.1:${APP_PORT}"

head_ "1. 服务与端口"
for svc in postgresql redis-server minio dms-app nginx; do
  if systemctl is-active --quiet "$svc"; then ok "$svc 运行中"; else bad "$svc 未运行（systemctl status $svc）"; fi
done
for p in 5432 6379 9000 8080 80; do
  if ss -ltnH "sport = :$p" 2>/dev/null | grep -q .; then ok "端口 $p 在监听"; else bad "端口 $p 没有监听"; fi
done
# 只应暴露 22/80；9000/9001/8081 等必须只监听回环
for p in 9000 9001 8081 8080; do
  if ss -ltnH "sport = :$p" 2>/dev/null | grep -qE "0\.0\.0\.0:$p|\*:$p"; then
    bad "端口 $p 对外监听（应只监听 127.0.0.1）"
  else
    ok "端口 $p 未对外开放"
  fi
done

head_ "2. 数据库"
if PGPASSWORD="${DB_PASSWORD}" psql -h 127.0.0.1 -U dms -d dms -tAc "select 1" >/dev/null 2>&1; then
  ok "应用账号能连库"
else
  bad "应用账号连不上库（检查 /opt/dms/config/application-prod.yml 与 pg_hba）"
fi
tbl=$(PGPASSWORD="${DB_PASSWORD}" psql -h 127.0.0.1 -U dms -d dms -tAc \
  "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'" 2>/dev/null || echo 0)
[[ "${tbl:-0}" -gt 30 ]] && ok "业务表 ${tbl} 张" || bad "业务表只有 ${tbl:-0} 张，结构可能没灌进去"
for t in sys_user sys_role sys_menu sys_site_config doc_folder doc_file doc_audit_log doc_export_task; do
  cnt=$(PGPASSWORD="${DB_PASSWORD}" psql -h 127.0.0.1 -U dms -d dms -tAc "select count(*) from $t" 2>/dev/null || echo "ERR")
  if [[ "$cnt" == "ERR" ]]; then bad "表 $t 不存在"; else ok "表 $t 有 $cnt 行"; fi
done
migrations=$(PGPASSWORD="${DB_PASSWORD}" psql -h 127.0.0.1 -U dms -d dms -tAc \
  "select count(*) from dms_schema_migrations" 2>/dev/null || echo 0)
ok "已记录的迁移版本：${migrations:-0} 个"

head_ "3. 对象存储（MinIO）"
# shellcheck disable=SC1090
[[ -f "${MINIO_CRED_FILE}" ]] && . "${MINIO_CRED_FILE}"
if curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1; then ok "MinIO 健康检查通过"; else bad "MinIO 不可达"; fi
if command -v mc >/dev/null 2>&1; then
  if mc ls local/dms-files >/dev/null 2>&1; then ok "桶 dms-files 可访问"; else bad "桶 dms-files 不可访问（mc alias local 是否配好？）"; fi
  # 真写一个对象再删：只"能列桶"不代表应用账号有写权限
  probe="verify-deploy-$(date +%s).txt"
  echo "probe" > /tmp/"${probe}"
  if mc cp /tmp/"${probe}" "local/dms-files/files/_verify/${probe}" >/dev/null 2>&1; then
    ok "应用账号能写入对象"
    mc rm "local/dms-files/files/_verify/${probe}" >/dev/null 2>&1 && ok "应用账号能删除对象"
  else
    bad "应用账号写不进去（检查 MINIO_APP_* 与策略）"
  fi
  rm -f /tmp/"${probe}"
else
  bad "找不到 mc 客户端"
fi

head_ "4. 应用接口"
login=$(curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' -H "clientid: ${CID}" \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\",\"clientId\":\"${CID}\",\"grantType\":\"password\"}" 2>/dev/null || echo "")
token=$(echo "${login}" | jq -r '.data.access_token // empty' 2>/dev/null || true)
if [[ -n "${token}" ]]; then ok "管理员登录成功"; else bad "管理员登录失败：$(echo "${login}" | head -c 160)"; fi
if [[ -n "${token}" ]]; then
  perms=$(curl -fsS "${BASE}/system/user/getInfo" -H "Authorization: Bearer ${token}" -H "clientid: ${CID}" \
    | jq -r '.data.permissions | join(",")' 2>/dev/null || true)
  [[ "${perms}" == *"*:*:*"* ]] && ok "超管权限串正常（*:*:*）" || bad "超管权限串异常：${perms}"
  folders=$(curl -fsS "${BASE}/api/doc/folders/children?parentId=0" -H "Authorization: Bearer ${token}" -H "clientid: ${CID}" \
    | jq -r '.data | length' 2>/dev/null || echo "ERR")
  [[ "${folders}" != "ERR" ]] && ok "文档区接口可用（顶层 ${folders} 个）" || bad "文档区接口异常"
  flags=$(curl -fsS "${BASE}/api/perm/flags" -H "Authorization: Bearer ${token}" -H "clientid: ${CID}" \
    | jq -r '.data | length' 2>/dev/null || echo "ERR")
  [[ "${flags}" != "ERR" ]] && ok "权限位接口可用（${flags} 个位）" || bad "权限位接口异常"
  sysinfo=$(curl -fsS "${BASE}/api/doc/system/info" -H "Authorization: Bearer ${token}" -H "clientid: ${CID}" \
    | jq -r '.data.minio.ok' 2>/dev/null || echo "ERR")
  [[ "${sysinfo}" == "true" ]] && ok "系统信息接口可用（MinIO 正常）" || bad "系统信息接口异常（MinIO=${sysinfo}）"
fi

head_ "5. 前端与 nginx"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1/" || echo 000)
[[ "${code}" == "200" ]] && ok "首页 HTTP 200" || bad "首页 HTTP ${code}"
js=$(curl -s "http://127.0.0.1/" | grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' | head -1)
if [[ -n "${js}" ]]; then
  jscode=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1/${js}")
  [[ "${jscode}" == "200" ]] && ok "前端入口 JS 可访问（${js}）" || bad "前端 JS HTTP ${jscode}"
else
  bad "首页里找不到入口 JS（dist 是否放对？）"
fi
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1/auth/login" \
  -H 'Content-Type: application/json' -H "clientid: ${CID}" \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\",\"clientId\":\"${CID}\",\"grantType\":\"password\"}")
[[ "${code}" == "200" ]] && ok "经 nginx 的接口反代可用" || bad "经 nginx 的接口反代 HTTP ${code}"

head_ "7. OnlyOffice（装了才查）"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx onlyoffice; then
  FS_SECRET="$(python3 -c "import json;print(json.load(open('/opt/onlyoffice/local.json'))['storage']['fs']['secretString'])" 2>/dev/null || true)"
  NGX_SECRET="$(docker exec onlyoffice grep -o 'secure_link_secret [A-Za-z0-9]*' /etc/onlyoffice/documentserver/nginx/ds.conf 2>/dev/null | awk '{print $2}')"
  ENV_SECRET="$(docker exec onlyoffice printenv SECURE_LINK_SECRET 2>/dev/null || true)"
  if [[ -n "${FS_SECRET}" && "${FS_SECRET}" == "${NGX_SECRET}" && "${FS_SECRET}" == "${ENV_SECRET}" ]]; then
    ok "缓存链接签名密钥三处一致（不一致时所有 Office/PDF 预览会「下载失败」）"
  else
    bad "签名密钥不一致 → 文档预览会「下载失败」（local.json=${FS_SECRET:0:4}… nginx=${NGX_SECRET:0:4}… env=${ENV_SECRET:0:4}…）"
  fi
  # 顺带验一下 nginx 是否把文档服务需要的路径都代理过去了（漏 downloadfile 会让编辑器取不到文档）
  for pth in downloadfile savefile converter upload; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1/${pth}/__probe__")
    if [[ "${code}" == "405" ]]; then
      bad "POST /${pth} 返回 405：nginx 的 OnlyOffice 反代路径清单漏了它，文档会「下载失败」"
    else
      ok "POST /${pth} 已代理到文档服务（HTTP ${code}）"
    fi
  done
  curl -fsS http://127.0.0.1:8081/healthcheck >/dev/null 2>&1 && ok "文档服务健康检查通过" || bad "文档服务不可达"
else
  log "未安装 OnlyOffice（可选组件）：Office/PDF 预览将回落为服务端转 PDF + pdf.js"
fi

head_ "6. 定时任务与日志"
systemctl is-enabled --quiet dms-backup.timer && ok "备份 timer 已启用" || bad "备份 timer 未启用"
[[ -d /opt/dms/logs ]] && ok "日志目录存在" || bad "日志目录缺失"
journalctl -u dms-app -n 1 --no-pager >/dev/null 2>&1 && ok "journald 能读到应用日志" || bad "读不到应用日志"

echo
echo "==================== 自检结果：通过 ${PASS}，失败 ${FAIL} ===================="
if [[ ${FAIL} -gt 0 ]]; then
  echo "有失败项，请按上面的提示排查；详细日志：/opt/dms/logs/{dms,stdout,stderr}.log"
  exit 1
fi
echo "全部通过：http://${SERVER_HOST}/ 已可用"

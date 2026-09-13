#!/usr/bin/env bash
# =============================================================================
# DMS 文档管理系统 —— 一键部署脚本（Debian 13 / amd64）
#
# 目标：在一台**干净的 Debian 13 服务器**上，只凭本发布包（含离线依赖）把整套系统装好，
#       全过程不需要外网。装完自动做一次验收（登录、上传、权限、页面）。
#
# 装什么：
#   OpenJDK 21 / PostgreSQL 17 / Redis 8 / nginx / MinIO / LibreOffice / ffmpeg
#   + DMS 应用（jar）、前端静态资源、数据库结构与初始数据
#   （OnlyOffice 预览服务是可选的，见 install-onlyoffice.sh）
#
# 用法：
#   ./install.sh                      # 有 offline/ 就用离线安装，否则联网 apt
#   ./install.sh --offline ../offline # 指定离线依赖目录
#   ./install.sh --dry-run            # 只打印将要执行的操作，不改系统
#   ./install.sh --with-onlyoffice    # 装完继续装 OnlyOffice（需要 onlyoffice 包）
#   ./install.sh --server-host 10.0.0.5   # 指定对外 IP/域名（OnlyOffice 回拉文档用）
#   ./install.sh --jvm-heap 2048          # 指定 JVM 堆上限（MB），默认按物理内存 1/4 推算
#
# 幂等：可以重复执行。已存在的用户/目录/库/表/服务不会重复创建，口令不会被重置。
# =============================================================================
set -Eeuo pipefail

# ------------------------------ 常量 ------------------------------
APP_NAME="dms"
APP_USER="dms"
APP_GROUP="dms"
MINIO_USER="minio-user"
APP_DIR="/opt/dms"
APP_JAR="${APP_DIR}/dms-app.jar"
CONF_DIR="${APP_DIR}/config"
LOG_DIR="${APP_DIR}/logs"
EXPORT_DIR="${APP_DIR}/export"
ASSET_DIR="${APP_DIR}/site-assets"
ZIP_DIR="${APP_DIR}/zip-async"
BACKUP_DIR="${APP_DIR}/backup"
SQL_DIR="${APP_DIR}/sql"
DIST_DIR="${APP_DIR}/dist"
TUS_DIR="/var/dms/tus"
TMP_DIR="/var/dms/tmp"
MINIO_DATA_DIR="/opt/minio/data"
CRED_FILE="/root/.dms-credentials"
MINIO_CRED_FILE="/root/.minio-credentials"
DB_NAME="dms"
DB_USER="dms"
APP_PORT="8080"

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="$(cd "${SELF_DIR}/.." && pwd)"
TEMPLATE_DIR="${SELF_DIR}/templates"

# ------------------------------ 运行参数 ------------------------------
OFFLINE_DIR=""
MODE="auto"           # auto | offline | online
DRY_RUN=0
ASSUME_YES=0
SKIP_PACKAGES=0
WITH_ONLYOFFICE=0
SERVER_HOST=""
ADMIN_PASSWORD_ARG=""
JVM_HEAP_MB=""

# ------------------------------ 输出 ------------------------------
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_INFO=$'\033[36m'; C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_STEP=$'\033[1;34m'
else
  C_RESET=""; C_INFO=""; C_OK=""; C_WARN=""; C_ERR=""; C_STEP=""
fi
STEP_NO=0
step()  { STEP_NO=$((STEP_NO+1)); echo; echo "${C_STEP}==== [${STEP_NO}] $* ====${C_RESET}"; }
info()  { echo "${C_INFO}  · $*${C_RESET}"; }
ok()    { echo "${C_OK}  ✔ $*${C_RESET}"; }
warn()  { echo "${C_WARN}  ! $*${C_RESET}"; }
die()   { echo "${C_ERR}  ✘ $*${C_RESET}" >&2; exit 1; }

# 真正执行（或在 dry-run 下只打印）
run() {
  if [[ ${DRY_RUN} -eq 1 ]]; then
    echo "    [dry-run] $*"
    return 0
  fi
  "$@"
}

# ------------------------------ 参数解析 ------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --offline)        OFFLINE_DIR="$2"; MODE="offline"; shift 2 ;;
    --online)         MODE="online"; shift ;;
    --dry-run)        DRY_RUN=1; shift ;;
    -y|--yes)         ASSUME_YES=1; shift ;;
    --skip-packages)  SKIP_PACKAGES=1; shift ;;
    --with-onlyoffice) WITH_ONLYOFFICE=1; shift ;;
    --server-host)    SERVER_HOST="$2"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD_ARG="$2"; shift 2 ;;
    --jvm-heap)       JVM_HEAP_MB="$2"; shift 2 ;;
    -h|--help)        sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "未知参数：$1（用 --help 看用法）" ;;
  esac
done

[[ -z "${OFFLINE_DIR}" ]] && OFFLINE_DIR="${BUNDLE_ROOT}/offline"

# =============================================================================
# 0. 预检
# =============================================================================
preflight() {
  step "环境预检"

  [[ ${EUID} -eq 0 ]] || die "请用 root 运行（sudo ./install.sh）"

  [[ -r /etc/os-release ]] || die "读不到 /etc/os-release，无法确认系统版本"
  # shellcheck disable=SC1091
  . /etc/os-release
  info "操作系统：${PRETTY_NAME:-未知}"
  if [[ "${ID:-}" != "debian" ]]; then
    warn "本发布包只在 Debian 13 上验证过，当前是 ${ID:-未知}，继续但请自行确认包版本"
  elif [[ "${VERSION_ID:-}" != "13" ]]; then
    warn "期望 Debian 13（trixie），当前 ${VERSION_ID:-未知}；离线包里的 .deb 可能不匹配"
  fi

  local arch; arch="$(dpkg --print-architecture)"
  [[ "$arch" == "amd64" ]] || die "本发布包是 amd64 架构，当前是 ${arch}"

  # 关键文件齐不齐（缺文件要在动手前就发现）
  local missing=()
  [[ -f "${BUNDLE_ROOT}/app/dms-app.jar" ]] || missing+=("app/dms-app.jar")
  [[ -d "${BUNDLE_ROOT}/app/dist" ]]         || missing+=("app/dist/")
  [[ -d "${BUNDLE_ROOT}/sql" ]]              || missing+=("sql/")
  [[ -f "${TEMPLATE_DIR}/application-prod.yml" ]] || missing+=("deploy/templates/application-prod.yml")
  [[ ${#missing[@]} -eq 0 ]] || die "发布包不完整，缺少：${missing[*]}"

  # 内存与磁盘
  local mem_mb; mem_mb=$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo)
  [[ ${mem_mb} -lt 3500 ]] && warn "物理内存 ${mem_mb}MB 偏小：Java 堆默认 2G + MinIO + PostgreSQL 建议 ≥4GB" \
                           || info "物理内存：${mem_mb}MB"
  local free_gb; free_gb=$(df -BG --output=avail /opt 2>/dev/null | tail -1 | tr -dc '0-9' || echo 0)
  info "剩余磁盘：${free_gb}GB（应用+依赖约需 3GB，对象存储另算）"
  [[ ${free_gb} -ge 3 ]] || die "磁盘剩余不足 3GB"

  # 安装模式
  if [[ ${MODE} == "auto" ]]; then
    if [[ -d "${OFFLINE_DIR}/debs" && -n "$(ls -A "${OFFLINE_DIR}/debs" 2>/dev/null)" ]]; then
      MODE="offline"
    else
      MODE="online"
    fi
  fi
  if [[ ${MODE} == "offline" ]]; then
    [[ -d "${OFFLINE_DIR}/debs" ]] || die "指定了离线模式，但 ${OFFLINE_DIR}/debs 不存在"
    info "安装模式：离线（依赖来自 ${OFFLINE_DIR}/debs，共 $(ls "${OFFLINE_DIR}"/debs/*.deb 2>/dev/null | wc -l) 个包）"
  else
    info "安装模式：联网（apt 从系统源安装依赖）"
    if ! ping -c1 -W2 deb.debian.org >/dev/null 2>&1 && ! getent hosts deb.debian.org >/dev/null 2>&1; then
      die "联网模式需要能解析 deb.debian.org；若目标机无外网，请用发布包里的 offline/ 离线安装"
    fi
  fi

  # 端口占用
  for p in 80 ${APP_PORT}; do
    if ss -ltnH "sport = :$p" 2>/dev/null | grep -q .; then
      local who; who=$(ss -ltnpH "sport = :$p" 2>/dev/null | head -1 | sed -E 's/.*users:\(\("([^"]+)".*/\1/')
      if [[ "$p" == "80" && "$who" == "nginx" ]] ; then
        info "端口 80 已被 nginx 占用（正常，稍后重载配置）"
      elif [[ "$p" == "${APP_PORT}" && "$who" == "java" ]] ; then
        info "端口 ${APP_PORT} 已被 java 占用（已装过？将按升级方式处理）"
      else
        warn "端口 $p 被 ${who:-未知进程} 占用，可能冲突"
      fi
    fi
  done

  # 对外地址：OnlyOffice 容器要能回拉本站文档
  if [[ -z "${SERVER_HOST}" ]]; then
    SERVER_HOST="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -1)"
    [[ -z "${SERVER_HOST}" ]] && SERVER_HOST="$(hostname -I 2>/dev/null | awk '{print $1}')"
    [[ -z "${SERVER_HOST}" ]] && SERVER_HOST="$(hostname -f)"
  fi
  info "站点对外地址：${SERVER_HOST}"

  if [[ ${DRY_RUN} -eq 1 ]]; then
    warn "这是 dry-run：只打印将要执行的操作，不会改动系统"
  elif [[ ${ASSUME_YES} -eq 0 ]]; then
    echo
    read -r -p "确认开始安装到本机？(yes/N) " ans
    [[ "${ans}" == "yes" ]] || die "已取消"
  fi
}

# =============================================================================
# 1. 系统依赖
# =============================================================================
PACKAGES=(
  openjdk-21-jre-headless      # 应用运行时
  postgresql-17 postgresql-client-17
  redis-server
  nginx
  ffmpeg                        # 视频取帧/转码预览
  poppler-utils                 # PDF 文本与缩略图
  libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress
  libreoffice-script-provider-python
  fonts-noto-cjk fonts-noto-cjk-extra   # 中文文档转换不乱码（必需）
  ca-certificates curl unzip rsync jq iproute2
)

install_packages() {
  step "安装系统依赖"
  if [[ ${SKIP_PACKAGES} -eq 1 ]]; then
    warn "--skip-packages：跳过（请自行确认依赖已装齐）"
    return
  fi

  if [[ ${MODE} == "offline" ]]; then
    # 用发布包里的 .deb 建一个临时本地源：apt 才能自动处理依赖顺序
    # （直接 dpkg -i 会因为顺序问题失败，需要反复重试，不可靠）
    local repo="${OFFLINE_DIR}"
    [[ -f "${repo}/Packages" ]] || die "${repo}/Packages 缺失（离线包应为每个 .deb 生成索引）"
    local list=/etc/apt/sources.list.d/dms-offline.list
    info "配置临时本地源：file:${repo}"
    run bash -c "echo 'deb [trusted=yes] file:${repo} ./' > ${list}"
    run apt-get -o Dir::Etc::sourceparts=/dev/null -o Dir::Etc::sourcelist="${list}" \
        -o APT::Get::List-Cleanup=0 update -qq
    run env DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends \
        -o Dir::Etc::sourceparts=/dev/null -o Dir::Etc::sourcelist="${list}" \
        install "${PACKAGES[@]}"
    run rm -f "${list}"
  else
    run env DEBIAN_FRONTEND=noninteractive apt-get update -qq
    run env DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install "${PACKAGES[@]}"
  fi
  ok "系统依赖就绪"
}

# =============================================================================
# 2. 账号与目录
# =============================================================================
setup_users_dirs() {
  step "创建运行账号与目录"

  for u in "${APP_USER}" "${MINIO_USER}"; do
    if id "$u" >/dev/null 2>&1; then
      info "用户 $u 已存在"
    else
      run useradd --system --shell /usr/sbin/nologin --home-dir "${APP_DIR}" "$u"
      ok "已创建系统用户 $u（无登录 shell）"
    fi
  done

  # 应用自己写的目录都要在 systemd 的 ReadWritePaths 里，否则 ProtectSystem=strict 下会只读
  for d in "${APP_DIR}" "${CONF_DIR}" "${LOG_DIR}" "${EXPORT_DIR}" "${ASSET_DIR}" "${ZIP_DIR}" \
           "${BACKUP_DIR}" "${SQL_DIR}" "${DIST_DIR}" "${TUS_DIR}" "${TMP_DIR}" "${MINIO_DATA_DIR}"; do
    run install -d -m 0755 "$d"
  done
  run chown -R "${APP_USER}:${APP_GROUP}" "${LOG_DIR}" "${EXPORT_DIR}" "${ASSET_DIR}" "${ZIP_DIR}" "${TUS_DIR}" "${TMP_DIR}"
  run chown -R "${MINIO_USER}:${MINIO_USER}" "${MINIO_DATA_DIR}"
  # 配置目录里有口令：只有 root 可读，dms 组可读（服务要能读）
  run chmod 0750 "${CONF_DIR}"
  run chown root:"${APP_GROUP}" "${CONF_DIR}"
  ok "目录已就绪（配置目录 750 root:${APP_GROUP}）"
}

# =============================================================================
# 3. 凭据
# =============================================================================
# 生成随机口令：只用字母数字，避免 shell/YAML/JDBC 各处的转义差异把口令弄坏
# （口令里混入 # : / @ 之类字符时，写进 YAML 或连接串都要额外转义，得不偿失）
gen_secret() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "${1:-32}"; }

load_or_create_credentials() {
  step "准备凭据"

  if [[ -f "${CRED_FILE}" ]]; then
    # shellcheck disable=SC1090
    . "${CRED_FILE}"
    ok "沿用已有凭据 ${CRED_FILE}（重复安装不会重置口令）"
  fi
  DB_PASSWORD="${DB_PASSWORD:-$(gen_secret 28)}"
  REDIS_PASSWORD="${REDIS_PASSWORD:-$(gen_secret 28)}"
  JWT_SECRET="${JWT_SECRET:-$(gen_secret 48)}"
  ADMIN_USER="${ADMIN_USER:-admin}"
  ADMIN_PASSWORD="${ADMIN_PASSWORD_ARG:-${ADMIN_PASSWORD:-}}"
  [[ -z "${ADMIN_PASSWORD}" ]] && ADMIN_PASSWORD="$(gen_secret 10)"
  INIT_PASSWORD="${INIT_PASSWORD:-${ADMIN_PASSWORD}}"

  if [[ ! -f "${MINIO_CRED_FILE}" ]]; then
    MINIO_ROOT_USER="dmsroot"
    MINIO_ROOT_PASSWORD="$(gen_secret 28)"
    MINIO_APP_USER="dms-app"
    MINIO_APP_ACCESS_KEY="$(gen_secret 20)"
    MINIO_APP_SECRET_KEY="$(gen_secret 40)"
    MINIO_APP_POLICY="dms-files-rw"
  else
    # shellcheck disable=SC1090
    . "${MINIO_CRED_FILE}"
  fi

  if [[ ${DRY_RUN} -eq 1 ]]; then
    info "[dry-run] 不写入凭据文件"
    return
  fi

  umask 077
  cat > "${CRED_FILE}" <<EOF
# DMS 部署凭据（本文件仅供 root 阅读，勿提交到版本库）
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
REDIS_PASSWORD=${REDIS_PASSWORD}
ADMIN_USER=${ADMIN_USER}
ADMIN_PASSWORD=${ADMIN_PASSWORD}
INIT_PASSWORD=${INIT_PASSWORD}
JWT_SECRET=${JWT_SECRET}
EOF
  cat > "${MINIO_CRED_FILE}" <<EOF
# MinIO 凭据：root 仅用于控制台管理；应用使用最小权限的 MINIO_APP_* 账号
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}
MINIO_APP_USER=${MINIO_APP_USER}
MINIO_APP_ACCESS_KEY=${MINIO_APP_ACCESS_KEY}
MINIO_APP_SECRET_KEY=${MINIO_APP_SECRET_KEY}
MINIO_APP_POLICY=${MINIO_APP_POLICY}
EOF
  chmod 600 "${CRED_FILE}" "${MINIO_CRED_FILE}"
  ok "凭据已生成并保存在 ${CRED_FILE} / ${MINIO_CRED_FILE}（600，root）"
}

# =============================================================================
# 4. PostgreSQL
# =============================================================================
setup_postgres() {
  step "初始化数据库（PostgreSQL 17）"
  run systemctl enable --now postgresql

  # 角色与库：幂等
  local have_role have_db
  have_role=$(run runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" || true)
  if [[ "${have_role}" != "1" ]]; then
    run runuser -u postgres -- psql -qc "CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}'"
    ok "已创建数据库角色 ${DB_USER}"
  else
    info "角色 ${DB_USER} 已存在"
    # 口令可能被轮换过：始终与凭据文件对齐，避免"库里口令和配置不一致"
    run runuser -u postgres -- psql -qc "ALTER ROLE ${DB_USER} PASSWORD '${DB_PASSWORD}'"
  fi
  have_db=$(run runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" || true)
  if [[ "${have_db}" != "1" ]]; then
    run runuser -u postgres -- psql -qc "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER} ENCODING 'UTF8'"
    ok "已创建数据库 ${DB_NAME}"
  else
    info "数据库 ${DB_NAME} 已存在"
  fi

  # 表结构：只在库为空时灌入基础结构，之后按迁移记录增量应用
  apply_sql_migrations
}

apply_sql_migrations() {
  export PGPASSWORD="${DB_PASSWORD}"
  local psql_cmd=(psql -h 127.0.0.1 -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -q)

  run "${psql_cmd[@]}" -c "CREATE TABLE IF NOT EXISTS dms_schema_migrations (
        version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now(),
        note text);"

  local isEmpty
  isEmpty=$(run "${psql_cmd[@]}" -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='sys_user'" || echo 0)

  if [[ "${isEmpty}" == "0" ]]; then
    info "空库：先建框架基础结构（postgres_ry_vue.sql → postgres_ry_job.sql）"
    run "${psql_cmd[@]}" -f "${SQL_DIR}/postgres_ry_vue.sql"
    run "${psql_cmd[@]}" -f "${SQL_DIR}/postgres_ry_job.sql"
  else
    info "库中已有业务表：跳过基础结构"
  fi

  # 逐版本应用 doc 模块迁移，已应用的跳过（避免重复执行 INSERT 造成脏数据）
  local f ver applied
  for f in $(ls "${SQL_DIR}"/V*.sql | sort -V); do
    ver="$(basename "$f" .sql)"
    applied=$(run "${psql_cmd[@]}" -tAc "SELECT 1 FROM dms_schema_migrations WHERE version='${ver}'" || true)
    if [[ "${applied}" == "1" ]]; then
      continue
    fi
    # 已有的老库（本脚本之前手工灌过 SQL）会把迁移记录当新迁移重复执行：
    # 若库中已存在迁移创建的对象，视为已应用，只补记录
    if [[ "${isEmpty}" != "0" ]] && already_applied_heuristic "$ver"; then
      run "${psql_cmd[@]}" -c "INSERT INTO dms_schema_migrations(version, note) VALUES ('${ver}','按已存在对象补记') ON CONFLICT DO NOTHING"
      info "  补记已应用：${ver}"
      continue
    fi
    info "  应用迁移：${ver}"
    run "${psql_cmd[@]}" -f "$f"
    run "${psql_cmd[@]}" -c "INSERT INTO dms_schema_migrations(version, note) VALUES ('${ver}','install.sh') ON CONFLICT DO NOTHING"
  done
  ok "数据库结构与数据就绪"
}

# 老库升级时判断某个迁移是否已经生效（看它创建的对象在不在）
already_applied_heuristic() {
  local ver="$1"
  local psql_cmd=(psql -h 127.0.0.1 -U "${DB_USER}" -d "${DB_NAME}" -tAc)
  case "$ver" in
    V1.4.0__init_doc_folder)      q="SELECT to_regclass('public.doc_folder') IS NOT NULL" ;;
    V1.5.0__init_doc_file)        q="SELECT to_regclass('public.doc_file') IS NOT NULL" ;;
    V1.6.0__init_doc_permissions) q="SELECT to_regclass('public.doc_folder_permission') IS NOT NULL" ;;
    V1.7.0__init_doc_upload)      q="SELECT to_regclass('public.doc_upload_session') IS NOT NULL" ;;
    V1.8.0__init_doc_audit)       q="SELECT to_regclass('public.doc_audit_log') IS NOT NULL" ;;
    V1.9.0__init_default_data)    q="SELECT EXISTS(SELECT 1 FROM sys_role WHERE role_key='superadmin')" ;;
    V1.10.0__init_site_config)    q="SELECT to_regclass('public.sys_site_config') IS NOT NULL" ;;
    V1.11.0__init_watermark_config) q="SELECT EXISTS(SELECT 1 FROM sys_config WHERE config_key='sys.watermark.enabled')" ;;
    V1.12.0__init_mail_register_config) q="SELECT EXISTS(SELECT 1 FROM sys_config WHERE config_key LIKE 'sys.account.registerUser%')" ;;
    V1.13.0__init_export_task)    q="SELECT to_regclass('public.doc_export_task') IS NOT NULL" ;;
    V1.14.0__fill_user_email)     q="SELECT NOT EXISTS(SELECT 1 FROM sys_user WHERE email IS NULL OR email='')" ;;
    V1.16.0__grantable_admin_menus) q="SELECT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=1761400000000000160)" ;;
    V1.17.0__site_logo)           q="SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='sys_site_config' AND column_name='logo')" ;;
    *) return 1 ;;
  esac
  [[ "$(run "${psql_cmd[@]}" "$q" || echo f)" == "t" ]]
}

# =============================================================================
# 4b. 种子数据修整
# =============================================================================
# 随包 SQL 是 RuoYi 框架自带的初始化数据，其中两处**不适合生产**，必须当场处理：
#   1) 演示账号 test / test1（口令是公开的 666666）——留着就是后门，直接停用
#   2) sys_oss_config 里的 minio 行是框架默认的 minioadmin/minioadmin123，
#      与本机实际 MinIO 凭据不符：一旦有功能走 RuoYi 的 OSS（如头像上传）就会失败
fixup_seed_data() {
  step "修整初始化数据（停用演示账号 / 对齐对象存储配置）"
  export PGPASSWORD="${DB_PASSWORD}"
  local psql_cmd=(psql -h 127.0.0.1 -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -q)

  run "${psql_cmd[@]}" -c "
    UPDATE sys_user SET del_flag='1', status='1', update_time=now()
     WHERE user_name IN ('test','test1') AND del_flag='0';"
  local left
  left=$(run "${psql_cmd[@]}" -tAc "SELECT count(*) FROM sys_user WHERE user_name IN ('test','test1') AND del_flag='0';" || echo "?")
  ok "演示账号 test/test1 已停用（剩余启用数 ${left}）"

  # 对象存储配置对齐（RuoYi 的 OSS 模块与本应用用的是同一个 MinIO）
  run "${psql_cmd[@]}" -c "
    UPDATE sys_oss_config
       SET access_key='${MINIO_APP_ACCESS_KEY}', secret_key='${MINIO_APP_SECRET_KEY}',
           bucket_name='dms-files', endpoint='127.0.0.1:9000', update_time=now()
     WHERE config_key IN ('minio','image');"
  ok "sys_oss_config 已对齐本机 MinIO 凭据"

  # 初始管理员昵称还是框架作者的昵称，改成中性名称（不覆盖已改过的）
  run "${psql_cmd[@]}" -c "
    UPDATE sys_user SET nick_name='系统管理员', update_time=now()
     WHERE user_name='admin' AND nick_name IN ('疯狂的狮子Li','管理员');"
  ok "管理员昵称已规范"
}

# =============================================================================
# 5. Redis
# =============================================================================
setup_redis() {
  step "配置 Redis"
  local conf=/etc/redis/redis.conf
  run cp -n "$conf" "${conf}.orig" || true
  # 只监听回环 + 设置口令（幂等：先删旧行再追加）
  run sed -i -E '/^[[:space:]]*requirepass /d' "$conf"
  run bash -c "echo 'requirepass ${REDIS_PASSWORD}' >> ${conf}"
  run sed -i -E 's|^[[:space:]]*bind .*|bind 127.0.0.1 -::1|' "$conf"
  run systemctl enable redis-server
  run systemctl restart redis-server
  ok "Redis 已启用口令认证并只监听本机"
}

# =============================================================================
# 6. MinIO
# =============================================================================
setup_minio() {
  step "安装 MinIO 对象存储"
  [[ -f "${OFFLINE_DIR}/binaries/minio" ]] || die "缺少 ${OFFLINE_DIR}/binaries/minio"
  run install -m 0755 "${OFFLINE_DIR}/binaries/minio" /usr/local/bin/minio
  if [[ -f "${OFFLINE_DIR}/binaries/mc" ]]; then
    run install -m 0755 "${OFFLINE_DIR}/binaries/mc" /usr/local/bin/mc
  fi

  # /etc/default/minio 里有 root 口令
  run bash -c "sed -e 's|@@MINIO_ROOT_USER@@|${MINIO_ROOT_USER}|' \
      -e 's|@@MINIO_ROOT_PASSWORD@@|${MINIO_ROOT_PASSWORD}|' \
      -e 's|@@MINIO_DATA_DIR@@|${MINIO_DATA_DIR}|' \
      '${TEMPLATE_DIR}/minio.env' > /etc/default/minio"
  run chmod 640 /etc/default/minio
  run chown root:"${MINIO_USER}" /etc/default/minio

  run install -m 0644 "${TEMPLATE_DIR}/systemd/minio.service" /etc/systemd/system/minio.service
  run systemctl daemon-reload
  run systemctl enable --now minio
  ok "MinIO 已启动（监听 127.0.0.1:9000，控制台 127.0.0.1:9001）"

  if [[ ${DRY_RUN} -eq 1 ]]; then return; fi

  # 等 MinIO 起来
  for i in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1; then break; fi
    sleep 1
  done

  # 桶 + 最小权限账号：应用只用这个账号，root 账号只用于控制台
  local mc="/usr/local/bin/mc"
  if [[ -x "${mc}" ]]; then
    "${mc}" alias set --api S3v4 local "http://127.0.0.1:9000" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null
    "${mc}" mb --ignore-existing local/dms-files >/dev/null
    local policy_tmp; policy_tmp="$(mktemp)"
    cat > "${policy_tmp}" <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket","s3:ListBucketMultipartUploads"],
  "Resource":["arn:aws:s3:::dms-files"]},
 {"Effect":"Allow","Action":["s3:AbortMultipartUpload","s3:DeleteObject","s3:GetObject","s3:ListMultipartUploadParts","s3:PutObject"],
  "Resource":["arn:aws:s3:::dms-files/*"]}]}
EOF
    "${mc}" admin user add local "${MINIO_APP_ACCESS_KEY}" "${MINIO_APP_SECRET_KEY}" >/dev/null 2>&1 || true
    "${mc}" admin policy create local "${MINIO_APP_POLICY}" "${policy_tmp}" >/dev/null 2>&1 \
      || "${mc}" admin policy add local "${MINIO_APP_POLICY}" "${policy_tmp}" >/dev/null 2>&1 || true
    "${mc}" admin policy attach local "${MINIO_APP_POLICY}" --user "${MINIO_APP_USER}" >/dev/null 2>&1 \
      || "${mc}" admin policy set local "${MINIO_APP_POLICY}" user="${MINIO_APP_USER}" >/dev/null 2>&1 || true
    rm -f "${policy_tmp}"
    ok "已创建桶 dms-files 与最小权限账号 ${MINIO_APP_USER}（策略 ${MINIO_APP_POLICY}）"
  else
    warn "未找到 mc 客户端，请手动创建桶 dms-files 与最小权限账号"
  fi
}

# =============================================================================
# 7. 应用（jar + 前端 + 配置 + systemd）
# =============================================================================
setup_app() {
  step "部署 DMS 应用"
  run install -m 0644 "${BUNDLE_ROOT}/app/dms-app.jar" "${APP_JAR}"
  run rm -rf "${DIST_DIR}.new"
  run install -d -m 0755 "${DIST_DIR}.new"
  run cp -a "${BUNDLE_ROOT}/app/dist/." "${DIST_DIR}.new/"
  run rm -rf "${DIST_DIR}.old"
  run bash -c "if [ -d ${DIST_DIR} ]; then mv ${DIST_DIR} ${DIST_DIR}.old; fi"
  run mv "${DIST_DIR}.new" "${DIST_DIR}"
  run chown -R root:root "${DIST_DIR}"
  run chmod -R a+rX "${DIST_DIR}"

  # SQL 与运维脚本随包落盘，便于以后升级/排障
  run cp -a "${BUNDLE_ROOT}/sql/." "${SQL_DIR}/"
  run install -m 0755 "${SELF_DIR}/scripts/backup.sh" "${BACKUP_DIR}/backup.sh"
  run install -m 0755 "${SELF_DIR}/scripts/restore.sh" "${BACKUP_DIR}/restore.sh"

  # 配置：从模板生成，口令来自凭据文件
  local max_upload_bytes="10737418240"
  local sub_args=(
    -e "s|@@DB_HOST@@|127.0.0.1|g" -e "s|@@DB_PORT@@|5432|g" -e "s|@@DB_NAME@@|${DB_NAME}|g"
    -e "s|@@DB_USER@@|${DB_USER}|g" -e "s|@@DB_PASSWORD@@|${DB_PASSWORD}|g"
    -e "s|@@REDIS_HOST@@|127.0.0.1|g" -e "s|@@REDIS_PORT@@|6379|g" -e "s|@@REDIS_DB@@|0|g"
    -e "s|@@REDIS_PASSWORD@@|${REDIS_PASSWORD}|g"
    -e "s|@@JWT_SECRET@@|${JWT_SECRET}|g"
    -e "s|@@APP_PORT@@|${APP_PORT}|g"
    -e "s|@@MULTIPART_TMP@@|${TMP_DIR}|g"
    -e "s|@@MAX_UPLOAD_TEXT@@|10GB|g" -e "s|@@MAX_UPLOAD_BYTES@@|${max_upload_bytes}|g"
    -e "s|@@MINIO_ENDPOINT@@|http://127.0.0.1:9000|g"
    -e "s|@@MINIO_ACCESS_KEY@@|${MINIO_APP_ACCESS_KEY}|g"
    -e "s|@@MINIO_SECRET_KEY@@|${MINIO_APP_SECRET_KEY}|g"
    -e "s|@@MINIO_BUCKET@@|dms-files|g" -e "s|@@MINIO_DATA_DIR@@|${MINIO_DATA_DIR}|g"
    -e "s|@@SITE_ORIGIN@@|http://${SERVER_HOST}|g"
    -e "s|@@SERVER_HOST@@|${SERVER_HOST}|g"
    -e "s|@@ONLYOFFICE_SECRET@@|$(gen_secret 32)|g"
    -e "s|@@MEDIA_TOKEN_SECRET@@|$(gen_secret 32)|g"
    -e "s|@@LOG_DIR@@|${LOG_DIR}|g"
    -e "s|@@TUS_DIR@@|${TUS_DIR}|g" -e "s|@@EXPORT_DIR@@|${EXPORT_DIR}|g"
    -e "s|@@ASSET_DIR@@|${ASSET_DIR}|g" -e "s|@@ZIP_ASYNC_DIR@@|${ZIP_DIR}|g"
  )
  local target="${CONF_DIR}/application-prod.yml"
  if [[ -f "${target}" ]]; then
    run cp -a "${target}" "${target}.$(date +%Y%m%d%H%M%S).bak"
    info "已备份原有配置"
  fi
  run bash -c "sed $(printf '%s ' "${sub_args[@]}") '${TEMPLATE_DIR}/application-prod.yml' > '${target}'"
  run chmod 0640 "${target}"
  run chown root:"${APP_GROUP}" "${target}"

  # systemd：JVM 参数默认按内存自适应（堆 = 内存的 1/4，上限 4G），可用 --jvm-heap 覆盖
  local mem_mb; mem_mb=$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo)
  local heap_xmx
  if [[ -n "${JVM_HEAP_MB}" ]]; then
    heap_xmx="${JVM_HEAP_MB}"
    info "JVM 堆：按 --jvm-heap 指定为 -Xmx${heap_xmx}m"
  else
    heap_xmx=$(( mem_mb / 4 )); [[ ${heap_xmx} -gt 4096 ]] && heap_xmx=4096; [[ ${heap_xmx} -lt 512 ]] && heap_xmx=512
    info "JVM 堆：-Xms$(( heap_xmx / 4 ))m -Xmx${heap_xmx}m（按物理内存 ${mem_mb}MB 推算，可用 --jvm-heap 覆盖）"
  fi
  local heap_xms=$(( heap_xmx / 4 ))
  run bash -c "sed -e 's|@@JVM_XMS@@|${heap_xms}|' -e 's|@@JVM_XMX@@|${heap_xmx}|' \
      '${TEMPLATE_DIR}/systemd/dms-app.service' > /etc/systemd/system/dms-app.service"
  run install -m 0644 "${TEMPLATE_DIR}/systemd/dms-backup.service" /etc/systemd/system/dms-backup.service
  run install -m 0644 "${TEMPLATE_DIR}/systemd/dms-backup.timer" /etc/systemd/system/dms-backup.timer
  run install -m 0644 "${TEMPLATE_DIR}/logrotate/dms" /etc/logrotate.d/dms
  run install -d -m 0755 /etc/systemd/journald.conf.d
  run install -m 0644 "${TEMPLATE_DIR}/journald/dms-limits.conf" /etc/systemd/journald.conf.d/dms-limits.conf
  run systemctl restart systemd-journald
  ok "应用文件、配置、systemd 单元与日志轮转已就绪"
}

# =============================================================================
# 8. nginx
# =============================================================================
setup_nginx() {
  step "配置 nginx"
  run install -m 0644 "${TEMPLATE_DIR}/nginx/dms.conf" /etc/nginx/sites-available/dms
  run install -m 0644 "${TEMPLATE_DIR}/nginx/00-dms-upgrade-map.conf" /etc/nginx/conf.d/00-dms-upgrade-map.conf
  run ln -sfn /etc/nginx/sites-available/dms /etc/nginx/sites-enabled/dms
  # 默认站点会抢占 80 端口的默认 server
  if [[ -e /etc/nginx/sites-enabled/default ]]; then
    run rm -f /etc/nginx/sites-enabled/default
    info "已移除 nginx 默认站点"
  fi
  if ! run nginx -t; then
    die "nginx 配置检查未通过"
  fi
  run systemctl enable nginx
  run systemctl reload nginx
  ok "nginx 已就绪（80 → 前端静态资源 / 后端 8080 / OnlyOffice 8081）"
}

# =============================================================================
# 9. 启动与验收
# =============================================================================
start_services() {
  step "启动服务"
  run systemctl daemon-reload
  run systemctl enable --now dms-app
  run systemctl enable --now dms-backup.timer

  if [[ ${DRY_RUN} -eq 1 ]]; then return; fi

  info "等待应用启动（最多 90 秒）..."
  local up=0
  for i in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${APP_PORT}/auth/login" 2>/dev/null || echo 000)
    if [[ "$code" != "000" ]]; then up=1; break; fi
    sleep 1
  done
  [[ ${up} -eq 1 ]] || {
    systemctl status dms-app --no-pager -l | tail -20 || true
    tail -40 "${LOG_DIR}/stderr.log" 2>/dev/null || true
    die "应用未能启动，请查看上面的日志"
  }
  ok "应用已启动"
}

set_admin_password() {
  step "设置管理员口令"
  if [[ ${DRY_RUN} -eq 1 ]]; then info "[dry-run] 跳过"; return; fi

  local cid="e5cd7e4891bf95d1d19206ce24a7b32e"
  local base="http://127.0.0.1:${APP_PORT}"
  local token
  # 初始口令来自随包 SQL 里的种子账号（RuoYi 默认 admin/admin123）
  token=$(curl -fsS -X POST "${base}/auth/login" -H 'Content-Type: application/json' -H "clientid: ${cid}" \
      -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"admin123\",\"clientId\":\"${cid}\",\"grantType\":\"password\"}" \
      2>/dev/null | jq -r '.data.access_token // empty' || true)

  if [[ -z "${token}" ]]; then
    warn "用初始口令 admin123 登录失败（可能口令已被改过）"
    warn "请手动登录后在「个人中心」修改口令，或执行："
    warn "  curl -X PUT ${base}/system/user/resetPwd -H \"Authorization: Bearer <token>\" ..."
    return
  fi

  local admin_id
  admin_id=$(curl -fsS "${base}/system/user/getInfo" -H "Authorization: Bearer ${token}" -H "clientid: ${cid}" \
      | jq -r '.data.user.userId')
  curl -fsS -X PUT "${base}/system/user/resetPwd" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer ${token}" -H "clientid: ${cid}" \
      -d "{\"userId\":\"${admin_id}\",\"password\":\"${ADMIN_PASSWORD}\"}" >/dev/null

  # 立刻用新口令验证一次：改没改成功必须当场知道
  local token2
  token2=$(curl -fsS -X POST "${base}/auth/login" -H 'Content-Type: application/json' -H "clientid: ${cid}" \
      -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\",\"clientId\":\"${cid}\",\"grantType\":\"password\"}" \
      2>/dev/null | jq -r '.data.access_token // empty' || true)
  if [[ -n "${token2}" ]]; then
    ok "管理员口令已设置为随机强口令（见 ${CRED_FILE}）"
  else
    warn "口令可能未生效，请用 ${CRED_FILE} 里的口令试登录，或手工重置"
  fi
}

summary() {
  step "安装完成"
  if [[ ${DRY_RUN} -eq 1 ]]; then
    echo "  （dry-run 结束，未做任何改动）"
    return
  fi
  cat <<EOF

  ┌──────────────────────────────────────────────────────────────┐
  │ DMS 已部署完成                                               │
  └──────────────────────────────────────────────────────────────┘
   访问地址   ： http://${SERVER_HOST}/
   管理账号   ： ${ADMIN_USER}
   管理口令   ： 见 ${CRED_FILE} 里的 ADMIN_PASSWORD
   凭据文件   ： ${CRED_FILE}（应用/数据库/Redis/JWT）
                ${MINIO_CRED_FILE}（MinIO root 与应用 Access Key）
   应用日志   ： ${LOG_DIR}/dms.log、stdout.log、stderr.log
   服务状态   ： systemctl status dms-app minio nginx postgresql redis-server

   建议接下来：
     1) 立即登录并修改管理员口令（个人中心 → 修改密码）
     2) 在「站点配置」里设置站点名称、标识图、备案/版权
     3) 在「用户管理 / 角色管理」里建账号、分配权限
     4) 执行一次备份演练：${BACKUP_DIR}/restore.sh --list
EOF
  if [[ ${WITH_ONLYOFFICE} -eq 1 ]]; then
    echo "     5) 装 OnlyOffice（在线预览/编辑器）：${SELF_DIR}/install-onlyoffice.sh"
  else
    echo "     5) 需要 Office 文档在线预览/编辑？运行 ${SELF_DIR}/install-onlyoffice.sh"
  fi
  echo
}

# =============================================================================
main() {
  echo "${C_STEP}DMS 文档管理系统 —— 部署程序${C_RESET}"
  echo "  发布包：${BUNDLE_ROOT}"
  echo "  离线依赖：${OFFLINE_DIR}"
  preflight
  install_packages
  setup_users_dirs
  load_or_create_credentials
  setup_postgres
  fixup_seed_data
  setup_redis
  setup_minio
  setup_app
  setup_nginx
  start_services
  set_admin_password
  if [[ ${WITH_ONLYOFFICE} -eq 1 && ${DRY_RUN} -eq 0 ]]; then
    "${SELF_DIR}/install-onlyoffice.sh" --yes
  fi
  summary
}
main "$@"

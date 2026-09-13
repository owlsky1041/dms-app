#!/usr/bin/env bash
# =============================================================================
# 构建 DMS 发布包
#
# 做三件事：
#   1. 从源码构建后端 jar（Maven）与前端静态资源（Vite）
#   2. 把 jar / dist / SQL / 部署脚本 / 配置模板 / 文档 组装成一棵发布目录
#   3. 打成一个核心包（不含 OnlyOffice）和一个可选的 OnlyOffice 包，并生成 SHA256 校验
#
# 前置：
#   - 已执行 fetch-offline-deps.sh（离线依赖在 release/offline/ 下）
#   - 本机能构建（JDK 21 + Maven + Node）；若只想要发布包而源码已构建过，用 --skip-build
#
# 用法：
#   ./build-release.sh                  # 全量构建
#   ./build-release.sh --skip-build     # 复用已有构建产物
#   ./build-release.sh --version 1.0.1  # 指定版本号
#   ./build-release.sh --with-onlyoffice-pkg   # 一并打出 OnlyOffice 包
# =============================================================================
set -Eeuo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SELF_DIR}/.." && pwd)"

# 兼容两种源码布局：
#   A) 发布工程就在后端仓库里  → release/ 的上一级就是后端，前端在同级目录
#   B) release/ 与 dms-app、dms-app-frontend 并列（开发工作区）
if [[ -d "${REPO_ROOT}/ruoyi-admin" ]]; then
  BACKEND_DIR="${REPO_ROOT}"
  if [[ -d "${REPO_ROOT}/../dms-app-frontend" ]]; then
    FRONTEND_DIR="$(cd "${REPO_ROOT}/../dms-app-frontend" && pwd)"
  else
    FRONTEND_DIR="${REPO_ROOT}/../dms-app-frontend"
  fi
else
  BACKEND_DIR="${REPO_ROOT}/dms-app"
  FRONTEND_DIR="${REPO_ROOT}/dms-app-frontend"
fi
OUT_DIR="${SELF_DIR}/dist"
VERSION="1.0.0"
SKIP_BUILD=0
WITH_OO_PKG=0
JAR_ARG=""
DIST_ARG=""
SQL_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --with-onlyoffice-pkg) WITH_OO_PKG=1; shift ;;
    # 允许在"没有源码树"的机器上组装（例如就在生产服务器上，依赖已经在本地）
    --jar)  JAR_ARG="$2"; shift 2 ;;
    --dist) DIST_ARG="$2"; shift 2 ;;
    --sql-dir) SQL_ARG="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

log() { echo "  · $*"; }
ok()  { echo "  ✔ $*"; }
die() { echo "  ✘ $*" >&2; exit 1; }

NAME="dms-release-${VERSION}"
STAGE="${OUT_DIR}/${NAME}"
mkdir -p "${OUT_DIR}"

# 防呆：产物输出目录与输入目录撞在一起时（例如 --dist dist），
# cp 会把发布目录往自己里面拷，报 "cannot copy a directory into itself"。
OUT_REAL="$(readlink -f "${OUT_DIR}" 2>/dev/null || echo "${OUT_DIR}")"
for arg in "${JAR_ARG}" "${DIST_ARG}" "${SQL_ARG}"; do
  [[ -z "${arg}" ]] && continue
  arg_real="$(readlink -f "${arg}" 2>/dev/null || echo "${arg}")"
  case "${arg_real}/" in
    "${OUT_REAL}/"*) die "${arg} 位于输出目录 ${OUT_DIR} 之内，会自己拷自己；请换一个输入路径" ;;
  esac
done

echo "==== 1/6 构建后端 ===="
if [[ -n "${JAR_ARG}" ]]; then
  log "使用指定 jar：${JAR_ARG}"
elif [[ ${SKIP_BUILD} -eq 1 ]]; then
  log "--skip-build：复用 ${BACKEND_DIR}/ruoyi-admin/target/ruoyi-admin.jar"
else
  ( cd "${BACKEND_DIR}" && \
    [[ -f "${REPO_ROOT}/tools/env.sh" ]] && source "${REPO_ROOT}/tools/env.sh" ; \
    mvn -s "${REPO_ROOT}/tools/maven-settings.xml" -q -o package -DskipTests -Drevision=6.0.0 \
        -pl ruoyi-admin -am )
fi
JAR="${JAR_ARG:-${BACKEND_DIR}/ruoyi-admin/target/ruoyi-admin.jar}"
[[ -f "${JAR}" ]] || die "找不到 jar：${JAR}"
ok "jar：$(du -h "${JAR}" | cut -f1)"

echo "==== 2/6 构建前端 ===="
if [[ -n "${DIST_ARG}" ]]; then
  log "使用指定前端产物：${DIST_ARG}"
elif [[ ${SKIP_BUILD} -eq 1 ]]; then
  log "--skip-build：复用 ${FRONTEND_DIR}/dist"
else
  ( cd "${FRONTEND_DIR}" && npx vite build )
fi
DIST="${DIST_ARG:-${FRONTEND_DIR}/dist}"
[[ -f "${DIST}/index.html" ]] || die "前端未构建（缺 index.html）：${DIST}"
ok "前端：$(du -sh "${DIST}" | cut -f1)"

echo "==== 3/6 组装发布目录 ===="
rm -rf "${STAGE}"
install -d "${STAGE}"/{app,config,sql,deploy,docs,offline}
cp -a "${JAR}" "${STAGE}/app/dms-app.jar"
cp -a "${DIST}" "${STAGE}/app/dist"
cp -a "${SELF_DIR}/deploy/." "${STAGE}/deploy/"
cp -a "${SELF_DIR}/docs/." "${STAGE}/docs/" 2>/dev/null || true
cp -a "${SELF_DIR}/README.md" "${STAGE}/README.md" 2>/dev/null || true

# SQL：RuoYi 基础结构 + doc 模块迁移
install -d "${STAGE}/sql"
if [[ -n "${SQL_ARG}" ]]; then
  [[ -f "${SQL_ARG}/postgres_ry_vue.sql" ]] || die "${SQL_ARG} 里缺 postgres_ry_vue.sql"
  cp -a "${SQL_ARG}/." "${STAGE}/sql/"
else
  cp -a "${BACKEND_DIR}/script/sql/postgres/postgres_ry_vue.sql" "${STAGE}/sql/" 2>/dev/null \
    || die "缺少 postgres_ry_vue.sql（后端仓库 script/sql/postgres/）"
  cp -a "${BACKEND_DIR}/script/sql/postgres/postgres_ry_job.sql" "${STAGE}/sql/" 2>/dev/null || true
  cp -a "${BACKEND_DIR}/ruoyi-modules/ruoyi-doc/src/main/resources/db/migration/"*.sql "${STAGE}/sql/"
fi
ok "SQL：$(ls "${STAGE}/sql" | wc -l) 个文件"

# 演示数据集（部署后可一键导入：./deploy/import-demo-data.sh）
if [[ -d "${SELF_DIR}/demo-data" ]]; then
  cp -a "${SELF_DIR}/demo-data" "${STAGE}/demo-data"
  ok "演示数据集：$(find "${STAGE}/demo-data/files" -type f 2>/dev/null | wc -l) 个文件"
fi

# 离线依赖
if [[ -d "${SELF_DIR}/offline/debs" ]]; then
  cp -a "${SELF_DIR}/offline/." "${STAGE}/offline/"
  ok "离线依赖：$(ls "${STAGE}/offline/debs"/*.deb 2>/dev/null | wc -l) 个 deb，$(du -sh "${STAGE}/offline" | cut -f1)"
else
  log "警告：offline/ 为空（未执行 fetch-offline-deps.sh），发布包将只能联网安装"
fi

# 校验和清单（写进包内，便于接收方核对）
# 权限必须显式规范化，不能依赖开发机的 umask：
#   * install.sh 是 644 → 部署方一执行就 "Permission denied"（踩过）
#   * 文档是 600 → 别人拿到包读不了文档
# 规则：目录 755、普通文件 644、脚本 755
find "${STAGE}" -type d -exec chmod 0755 {} + 2>/dev/null || true
find "${STAGE}" -type f -exec chmod 0644 {} + 2>/dev/null || true
find "${STAGE}" -name '*.sh' -exec chmod 0755 {} + 2>/dev/null || true

# macOS 打包会带上 ._* 这类 AppleDouble 文件（尤其是从 Finder/挂载卷复制的目录），
# 它们对 Linux 毫无意义，还会让"文件数对不上"的校验变得可疑，统一清掉
find "${STAGE}" -name '._*' -type f -delete 2>/dev/null || true
find "${STAGE}" -name '.DS_Store' -type f -delete 2>/dev/null || true

echo "==== 4/6 生成校验清单 ===="
( cd "${STAGE}" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
ok "SHA256SUMS：$(wc -l < "${STAGE}/SHA256SUMS") 个文件"

cat > "${STAGE}/VERSION" <<EOF
name=DMS 文档管理系统
version=${VERSION}
built_at=$(date '+%F %T %Z')
built_on=$(hostname) ($(uname -srm))
backend_jar=app/dms-app.jar ($(du -h "${JAR}" | cut -f1))
frontend=app/dist
os_target=Debian 13 (trixie) / amd64
EOF

echo "==== 5/6 打包 ===="
CORE_TGZ="${OUT_DIR}/${NAME}-core.tar.gz"
( cd "${OUT_DIR}" && tar czf "${CORE_TGZ}" "${NAME}" )
ok "核心包：$(du -h "${CORE_TGZ}" | cut -f1)  ${CORE_TGZ}"

OO_TGZ=""
if [[ ${WITH_OO_PKG} -eq 1 ]]; then
  # 明确要求打 OnlyOffice 包却没有镜像时**直接失败**：
  # 静默跳过会让人以为"两个包都打好了"，实际交付里少了一个（踩过一次）
  [[ -f "${SELF_DIR}/onlyoffice/documentserver.tar" ]] || die \
    "指定了 --with-onlyoffice-pkg，但找不到 ${SELF_DIR}/onlyoffice/documentserver.tar。\
先执行 fetch-offline-deps.sh --with-onlyoffice 准备镜像，或把镜像放到该路径"
fi
if [[ ${WITH_OO_PKG} -eq 1 && -f "${SELF_DIR}/onlyoffice/documentserver.tar" ]]; then
  OO_STAGE="${OUT_DIR}/${NAME}-onlyoffice"
  rm -rf "${OO_STAGE}"
  install -d "${OO_STAGE}/onlyoffice"
  mv "${SELF_DIR}/onlyoffice/documentserver.tar" "${OO_STAGE}/onlyoffice/"
  cp -a "${SELF_DIR}/deploy/install-onlyoffice.sh" "${OO_STAGE}/"
  install -d "${OO_STAGE}/deploy"
  cp -a "${SELF_DIR}/deploy/install-onlyoffice.sh" "${OO_STAGE}/deploy/"
  # 配置模板也要带上：install-onlyoffice.sh 会读它（缺了会退回内置默认，但带上更一致）
  install -d "${OO_STAGE}/deploy/templates/onlyoffice"
  cp -a "${SELF_DIR}/deploy/templates/onlyoffice/local.json" "${OO_STAGE}/deploy/templates/onlyoffice/" 2>/dev/null || true
  # 不再往 OnlyOffice 包里塞"只有几个 .deb"的假离线源：配成 apt 源会装不上、还误导人。
  # Docker 的离线包在核心包的 offline/debs 里（含完整依赖闭包），
  # install-onlyoffice.sh 会自动去那儿找，也可用 --offline-debs 显式指定。
  ( cd "${OUT_DIR}" && tar cf "${NAME}-onlyoffice.tar" "${NAME}-onlyoffice" )
  OO_TGZ="${OUT_DIR}/${NAME}-onlyoffice.tar"
  ok "OnlyOffice 包：$(du -h "${OO_TGZ}" | cut -f1)  ${OO_TGZ}"
  rm -rf "${OO_STAGE}"
fi

echo "==== 6/6 汇总 ===="
( cd "${OUT_DIR}" && sha256sum "${NAME}"*.tar* > "${NAME}-SHA256SUMS" 2>/dev/null || true )
ls -lh "${OUT_DIR}"/*.tar* "${OUT_DIR}"/*SHA256SUMS 2>/dev/null | awk '{print "    "$5"\t"$9}'
cat <<EOF

  发布目录（未压缩）：${STAGE}
  交付文件：${OUT_DIR}/
    ├── ${NAME}-core.tar.gz            核心包（含离线依赖，可离线部署）
$( [[ -n "${OO_TGZ}" ]] && echo "    ├── ${NAME}-onlyoffice.tar          OnlyOffice 镜像包（可选）" )
    └── ${NAME}-SHA256SUMS             校验和

  部署方操作：
    tar xzf ${NAME}-core.tar.gz && cd ${NAME}
    ./deploy/install.sh
    ./deploy/verify-deploy.sh
EOF

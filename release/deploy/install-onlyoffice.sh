#!/usr/bin/env bash
# =============================================================================
# 可选组件：OnlyOffice Document Server（Office 文档在线预览/编辑器）
#
# 为什么单独一个脚本、单独一个包：
#   它是个 3.3GB 的 Docker 镜像，占发布包的绝大部分体积，而且**不是必须的**。
#   不装它时：PDF/Office 预览仍可用（服务端用 LibreOffice 转成 PDF，前端 pdf.js 渲染），
#             只是没有 OnlyOffice 的原生观感与在线编辑。
#
# 用法：
#   ./install-onlyoffice.sh                 # 从同目录的 onlyoffice/ 镜像包安装
#   ./install-onlyoffice.sh --image-file /path/onlyoffice.tar
#   ./install-onlyoffice.sh --online        # 直接在联网环境 docker pull
#   ./install-onlyoffice.sh --offline-debs <核心包>/offline/debs   # 指定含 Docker 的离线源
# =============================================================================
set -Eeuo pipefail

IMAGE_FILE=""
ONLINE=0
ASSUME_YES=0
OFFLINE_DEBS=""   # 含 docker.io*.deb 的离线源目录（默认自动找核心包的 offline/debs）
LOCAL_JSON="/opt/onlyoffice/local.json"
FONTS_DIR="/opt/onlyoffice/fonts"
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image-file) IMAGE_FILE="$2"; shift 2 ;;
    --offline-debs) OFFLINE_DEBS="$2"; shift 2 ;;
    --online) ONLINE=1; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

[[ ${EUID} -eq 0 ]] || { echo "请用 root 运行" >&2; exit 1; }
log() { echo "  · $*"; }
ok()  { echo "  ✔ $*"; }

echo "==== OnlyOffice Document Server 安装 ===="

# ---- 1. Docker ----
# 找"能装 Docker 的离线源"：必须真的含 docker.io 的 deb，
# 只看目录存在是不够的——OnlyOffice 包里可能有个只有几个 deb 的目录，配成源会装不上。
find_offline_repo() {
  local cand
  for cand in "${OFFLINE_DEBS}" \
              "${SELF_DIR}/offline/debs" \
              "${SELF_DIR}/../offline/debs" \
              "${SELF_DIR}/../../offline/debs" \
              "/root/dms-release-1.0.0/offline/debs" \
              "/opt/dms/offline/debs"; do
    [[ -n "${cand}" ]] || continue
    if compgen -G "${cand}/docker.io_*.deb" >/dev/null 2>&1 || compgen -G "${cand}/docker*.deb" >/dev/null 2>&1; then
      dirname "${cand}"      # 传给 apt 的源目录（含 Packages 索引的那一层）
      return 0
    fi
  done
  return 1
}

if ! command -v docker >/dev/null 2>&1; then
  if REPO="$(find_offline_repo)"; then
    log "从离线源安装 Docker：${REPO}"
    echo "deb [trusted=yes] file:${REPO} ./" > /etc/apt/sources.list.d/dms-oo-offline.list
    apt-get -o Dir::Etc::sourceparts=/dev/null -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/dms-oo-offline.list update -qq
    DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends \
      -o Dir::Etc::sourceparts=/dev/null -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/dms-oo-offline.list \
      install docker.io containerd
    rm -f /etc/apt/sources.list.d/dms-oo-offline.list
  else
    # 找不到离线源就只能联网装；内网无外网时给明确指引，别让脚本默默失败
    if ! getent hosts deb.debian.org >/dev/null 2>&1; then
      echo "  ✘ 找不到含 Docker 的离线源，且无法解析 deb.debian.org" >&2
      echo "    请用 --offline-debs <核心包解压目录>/offline/debs 指定（核心包里带 Docker 的离线包）" >&2
      exit 1
    fi
    log "联网安装 Docker"
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install docker.io containerd
  fi
fi
systemctl enable --now docker
ok "Docker $(docker --version | awk '{print $3}' | tr -d ,)"

# ---- 2. 镜像 ----
if [[ -z "${IMAGE_FILE}" ]]; then
  IMAGE_FILE="${SELF_DIR}/onlyoffice/documentserver.tar"
fi
if [[ ${ONLINE} -eq 1 ]]; then
  log "docker pull onlyoffice/documentserver:latest（约 3.3GB）"
  docker pull onlyoffice/documentserver:latest
elif [[ -f "${IMAGE_FILE}" ]]; then
  log "导入镜像 ${IMAGE_FILE}（约 3.3GB，需要几分钟）"
  docker load -i "${IMAGE_FILE}"
else
  echo "找不到镜像文件 ${IMAGE_FILE}" >&2
  echo "请把 onlyoffice 发布包解压到 ${SELF_DIR}/onlyoffice/，或用 --image-file 指定路径，或用 --online" >&2
  exit 1
fi
docker image inspect onlyoffice/documentserver:latest >/dev/null
ok "镜像就绪"

# ---- 3. 配置与中文字体 ----
install -d -m 0755 /opt/onlyoffice "${FONTS_DIR}"

# ★ 关键：缓存链接的签名密钥必须三处一致 ★
#   文档服务给缓存地址（/cache/files/...）签名用的是 local.json 里的
#   storage.fs.secretString；nginx 用 $secure_link_secret 校验。
#   两者不一致时，**所有经 OnlyOffice 渲染的格式（PDF/Office/CAD…）都会弹「下载失败」**，
#   而图片、视频不受影响 —— 现象就是"除了图片和视频都打不开"。
#   官方文档明确要求三处（容器环境变量 SECURE_LINK_SECRET / local.json / ds.conf）取同一值。
#   这里统一生成一次、写到 local.json、并通过环境变量传给容器（容器启动脚本会写进 ds.conf）。
SECURE_LINK_SECRET_FILE="/opt/onlyoffice/.secure_link_secret"
if [[ -f "${SECURE_LINK_SECRET_FILE}" ]]; then
  SECURE_LINK_SECRET="$(cat "${SECURE_LINK_SECRET_FILE}")"
  log "沿用已有签名密钥 ${SECURE_LINK_SECRET_FILE}"
else
  SECURE_LINK_SECRET="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)"
  printf '%s' "${SECURE_LINK_SECRET}" > "${SECURE_LINK_SECRET_FILE}"
  chmod 600 "${SECURE_LINK_SECRET_FILE}"
  ok "已生成缓存链接签名密钥（${SECURE_LINK_SECRET_FILE}，600）"
fi

# JWT 默认关闭：容器只监听回环，对外只有 nginx 上那几个固定路径能进来。
# 若将来要打开 JWT：把 JWT_SECRET 与 /opt/dms/config/application-prod.yml 的
# dms.onlyoffice.secret 保持一致，并设 JWT_ENABLED=true。
if [[ -f "${LOCAL_JSON}" ]] && grep -q '"secretString"' "${LOCAL_JSON}" 2>/dev/null; then
  log "${LOCAL_JSON} 已存在且含 secretString，按其中的密钥对齐"
  SECURE_LINK_SECRET="$(python3 -c "import json;print(json.load(open('${LOCAL_JSON}'))['storage']['fs']['secretString'])" 2>/dev/null || echo "${SECURE_LINK_SECRET}")"
  printf '%s' "${SECURE_LINK_SECRET}" > "${SECURE_LINK_SECRET_FILE}"
  chmod 600 "${SECURE_LINK_SECRET_FILE}"
else
  # 优先用发布包里的模板；模板不在（脚本被单独拷走）时就地生成，保证脚本自洽
  if [[ -f "${SELF_DIR}/templates/onlyoffice/local.json" ]]; then
    sed "s|@@SECURE_LINK_SECRET@@|${SECURE_LINK_SECRET}|" \
        "${SELF_DIR}/templates/onlyoffice/local.json" > "${LOCAL_JSON}"
  else
    cat > "${LOCAL_JSON}" <<JSON
{
  "services": {
    "CoAuthoring": {
      "token": { "enable": { "request": { "inbox": false, "outbox": false }, "browser": false } },
      "request-filtering-agent": { "allowPrivateIPAddress": true, "allowMetaIPAddress": false }
    }
  },
  "storage": { "fs": { "secretString": "${SECURE_LINK_SECRET}" } }
}
JSON
  fi
  ok "已写入 ${LOCAL_JSON}（JWT 关闭；签名密钥已固定）"
fi

# 中文字体：不装字体的话，转换出来的 PDF/预览常常是方框（豆腐块）
if [[ -z "$(ls -A "${FONTS_DIR}" 2>/dev/null)" ]]; then
  for src in /usr/share/fonts/opentype/noto /usr/share/fonts/truetype/noto; do
    [[ -d "$src" ]] && cp -a "$src"/. "${FONTS_DIR}/" 2>/dev/null || true
  done
  # 让字体名与文档里引用的常见中文字体（宋体/黑体等）对上
  cat > /opt/onlyoffice/fonts-alias.xml <<'XML'
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <match target="pattern"><test name="family"><string>宋体</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK SC</string></edit></match>
  <match target="pattern"><test name="family"><string>SimSun</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK SC</string></edit></match>
  <match target="pattern"><test name="family"><string>黑体</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK SC</string></edit></match>
  <match target="pattern"><test name="family"><string>SimHei</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK SC</string></edit></match>
  <match target="pattern"><test name="family"><string>微软雅黑</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK SC</string></edit></match>
</fontconfig>
XML
  ok "已复制中文字体并建立字体别名（避免转换出方框）"
fi

# ---- 4. 容器 ----
if docker ps -a --format '{{.Names}}' | grep -qx onlyoffice; then
  log "容器已存在，重建以应用新配置"
  docker rm -f onlyoffice >/dev/null
fi
docker run -d --name onlyoffice --restart always \
  -p 127.0.0.1:8081:80 \
  -e JWT_ENABLED=false \
  -e SECURE_LINK_SECRET="${SECURE_LINK_SECRET}" \
  -v /opt/onlyoffice/local.json:/etc/onlyoffice/documentserver/local.json:ro \
  -v "${FONTS_DIR}":/usr/share/fonts/truetype/custom:ro \
  -v /opt/onlyoffice/fonts-alias.xml:/etc/fonts/conf.d/99-dms-cjk-alias.conf:ro \
  onlyoffice/documentserver:latest >/dev/null
ok "容器已启动（127.0.0.1:8081）"

log "等待文档服务就绪（最多 120 秒）"
for i in $(seq 1 120); do
  if curl -fsS http://127.0.0.1:8081/healthcheck >/dev/null 2>&1; then break; fi
  sleep 1
done
if curl -fsS http://127.0.0.1:8081/healthcheck >/dev/null 2>&1; then
  ok "OnlyOffice 健康检查通过"
else
  echo "  ! OnlyOffice 未在预期时间内就绪，查看日志：docker logs onlyoffice | tail -50" >&2
fi

systemctl reload nginx 2>/dev/null || true

# ---- 5. 自检：三处签名密钥必须一致，否则文档会「下载失败」 ----
echo "==== 签名密钥一致性自检 ===="
NGX_SECRET="$(docker exec onlyoffice grep -o 'secure_link_secret [A-Za-z0-9]*' \
  /etc/onlyoffice/documentserver/nginx/ds.conf 2>/dev/null | awk '{print $2}')"
ENV_SECRET="$(docker exec onlyoffice printenv SECURE_LINK_SECRET 2>/dev/null || true)"
FS_SECRET="$(python3 -c "import json;print(json.load(open('${LOCAL_JSON}'))['storage']['fs']['secretString'])" 2>/dev/null || true)"
printf '  local.json(storage.fs.secretString) = %s…\n  nginx(\$secure_link_secret)          = %s…\n  容器环境变量 SECURE_LINK_SECRET       = %s…\n' \
  "${FS_SECRET:0:4}" "${NGX_SECRET:0:4}" "${ENV_SECRET:0:4}"
if [[ -n "${NGX_SECRET}" && "${NGX_SECRET}" == "${FS_SECRET}" && "${ENV_SECRET}" == "${FS_SECRET}" ]]; then
  ok "三处一致 —— 文档预览可用"
else
  echo "  ✘ 三处不一致：文档会弹「下载失败」（图片/视频不受影响）" >&2
  echo "    处理：删除容器重建并确认 -e SECURE_LINK_SECRET=\$(cat /opt/onlyoffice/.secure_link_secret)" >&2
  exit 1
fi
cat <<'EOF'

  OnlyOffice 安装完成。请在「系统信息」页确认「MinIO / OnlyOffice」相关项，
  然后打开一个 docx/xlsx 文件验证在线预览。

  注意：nginx 里已包含 OnlyOffice 的反代规则（根路径资源 + 带版本号前缀资源，
  以及 /doc/ 协作会话端点）。若编辑器加载白屏，先看 nginx 错误日志：
    tail -f /var/log/nginx/dms-error.log
EOF

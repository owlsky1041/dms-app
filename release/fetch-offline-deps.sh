#!/usr/bin/env bash
# =============================================================================
# 收集离线部署依赖
#
# 在一台**联网的 Debian 13 / amd64** 机器上执行（生产服务器本身就是一台，可直接在上面跑）。
# 产物：
#   offline/debs/*.deb      应用所需的全部系统包（含依赖闭包）
#   offline/Packages        本地 apt 源索引（离线安装时 apt 靠它解决依赖顺序）
#   offline/binaries/minio  MinIO 服务端二进制（版本与线上一致）
#   offline/binaries/mc     MinIO 客户端（备份脚本要用）
#   offline/packages.list   包名-版本清单（写进技术文档）
#   onlyoffice/documentserver.tar   OnlyOffice 镜像（可选，约 3.3GB）
#
# 用法：
#   ./fetch-offline-deps.sh                 # 下载 deb + 复制本机二进制
#   ./fetch-offline-deps.sh --with-onlyoffice
#   ./fetch-offline-deps.sh --from-server root@192.168.9.62
#       从已有生产服务器取二进制与镜像（保证版本与已验证环境完全一致，推荐）
# =============================================================================
set -Eeuo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OFFLINE_DIR="${SELF_DIR}/offline"
ONLYOFFICE_DIR="${SELF_DIR}/onlyoffice"
FROM_SERVER=""
WITH_ONLYOFFICE=0
SSH_KEY="${DMS_SSH_KEY:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-onlyoffice) WITH_ONLYOFFICE=1; shift ;;
    --from-server)     FROM_SERVER="$2"; shift 2 ;;
    --ssh-key)         SSH_KEY="$2"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

log() { echo "  · $*"; }
ok()  { echo "  ✔ $*"; }
die() { echo "  ✘ $*" >&2; exit 1; }

[[ ${EUID} -eq 0 ]] || die "请用 root 运行（需要 apt 与 /var/cache/apt/archives）"
[[ "$(dpkg --print-architecture)" == "amd64" ]] || die "只支持 amd64"
. /etc/os-release
[[ "${VERSION_ID:-}" == "13" ]] || die "请在 Debian 13 上收集依赖（当前 ${VERSION_ID:-未知}）；离线 .deb 必须与目标系统同版本"

# 与 deploy/install.sh 中的 PACKAGES 保持一致
PACKAGES=(
  openjdk-21-jre-headless
  postgresql-17 postgresql-client-17
  redis-server
  nginx
  ffmpeg
  poppler-utils
  libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress
  libreoffice-script-provider-python
  fonts-noto-cjk fonts-noto-cjk-extra
  ca-certificates curl unzip rsync jq iproute2
)

mkdir -p "${OFFLINE_DIR}/debs" "${OFFLINE_DIR}/binaries"

# 在"空 dpkg 状态"下解析依赖闭包并下载到指定目录
#
# 为什么必须这么做：apt 认为依赖都已安装时，只会下载我们点名的包，
# 于是离线包里的依赖集是**不完整**的（干净服务器上会报
# "Depends: xxx but it is not installable"）。给 apt 一个空的 status 文件，
# 它就会按"什么都没装"重新算一遍完整闭包。
#
# 注意 sources.list 必须保留：只把状态清空、源仍指向官方镜像，
# 否则 apt 找不到任何包（"Unable to locate package"）。.deb 仍然从网络下载，
# 这个脚本本来就是"在联网机器上跑"。
download_closure() {
  local dest="$1"; shift
  local root; root="$(mktemp -d /tmp/dms-aptroot-XXXXXX)"
  mkdir -p "${root}"/{etc/apt/sources.list.d,var/lib/dpkg,var/lib/apt/lists/partial,var/cache/apt/archives/partial}
  : > "${root}/var/lib/dpkg/status"
  cp -a /var/lib/apt/lists/. "${root}/var/lib/apt/lists/" 2>/dev/null || true
  # 拷贝源定义（含 deb822 格式的 .sources）与信任配置，让 apt 认得出缓存索引的来源
  cp -a /etc/apt/sources.list "${root}/etc/apt/" 2>/dev/null || true
  cp -a /etc/apt/sources.list.d/. "${root}/etc/apt/sources.list.d/" 2>/dev/null || true
  cp -a /etc/apt/trusted.gpg.d "${root}/etc/apt/" 2>/dev/null || true
  DEBIAN_FRONTEND=noninteractive apt-get \
    -o Dir="${root}" \
    -o Dir::State::status="${root}/var/lib/dpkg/status" \
    -o Dir::Cache="${root}/var/cache/apt" \
    -o Dir::State::lists="${root}/var/lib/apt/lists" \
    -o APT::Get::List-Cleanup=0 \
    -y --download-only --no-install-recommends install "$@"
  find "${root}/var/cache/apt/archives" -maxdepth 1 -name '*.deb' -exec cp -n {} "${dest}/" \;
  rm -rf "${root}"
}

echo "==== 1/5 下载系统包与依赖闭包 ===="
DEBIAN_FRONTEND=noninteractive apt-get update -qq
log "按空状态解析依赖闭包（这一步决定离线包能不能在干净服务器上装通）"
download_closure "${OFFLINE_DIR}/debs" "${PACKAGES[@]}" >/dev/null
count=$(ls "${OFFLINE_DIR}/debs"/*.deb 2>/dev/null | wc -l)
[[ ${count} -gt 20 ]] || die "只拿到 ${count} 个 .deb，下载明显不完整"
ok "${count} 个 .deb，共 $(du -sh "${OFFLINE_DIR}/debs" | cut -f1)"

echo "==== 2/5 生成本地 apt 源索引 ===="
# 不用 dpkg-scanpackages：那需要 dpkg-dev，而目标机可能没有。
# 这里用 dpkg-deb 直接读控制信息生成 Packages/Release，任何装了 dpkg 的机器都能生成。
python3 - "${OFFLINE_DIR}" <<'PY'
import gzip, hashlib, os, subprocess, sys, pathlib, base64
root = pathlib.Path(sys.argv[1]); debs = sorted((root/'debs').glob('*.deb'))
entries = []
for d in debs:
    fields = {}
    for line in subprocess.run(['dpkg-deb','-f',str(d)], capture_output=True, text=True, check=True).stdout.splitlines():
        if ':' in line and not line.startswith(' '):
            k, v = line.split(':', 1); fields[k.strip()] = v.strip()
    # Filename/Size/SHA256 是索引必需字段
    fields['Filename'] = 'debs/' + d.name
    fields['Size'] = str(d.stat().st_size)
    fields['SHA256'] = hashlib.sha256(d.read_bytes()).hexdigest()
    order = ['Package','Version','Architecture','Maintainer','Installed-Size','Depends','Pre-Depends','Recommends','Suggests','Conflicts','Breaks','Replaces','Provides','Section','Priority','Homepage','Description','Filename','Size','SHA256']
    out = []
    for k in order:
        if k in fields:
            out.append('%s: %s' % (k, fields[k]))
    for k in fields:
        if k not in order:
            out.append('%s: %s' % (k, fields[k]))
    entries.append('\n'.join(out))
(root/'Packages').write_text('\n\n'.join(entries) + '\n')
with gzip.open(root/'Packages.gz','wb') as f:
    f.write((root/'Packages').read_bytes())
print('    Packages 索引：%d 个包' % len(entries))
PY
ok "已生成 ${OFFLINE_DIR}/Packages"

echo "==== 3/5 准备 MinIO 二进制 ===="
if [[ -n "${FROM_SERVER}" ]]; then
  SSH=(ssh -o StrictHostKeyChecking=no); SCP=(scp -o StrictHostKeyChecking=no)
  [[ -n "${SSH_KEY}" ]] && { SSH+=(-i "${SSH_KEY}"); SCP+=(-i "${SSH_KEY}"); }
  for b in minio mc; do
    log "从 ${FROM_SERVER} 复制 ${b}"
    "${SCP[@]}" -q "${FROM_SERVER}:/usr/local/bin/${b}" "${OFFLINE_DIR}/binaries/${b}"
  done
elif [[ -x /usr/local/bin/minio && -x /usr/local/bin/mc ]]; then
  # 就在已部署的服务器上跑：直接用本机这两份，版本与已验证环境 100% 一致
  log "使用本机 /usr/local/bin 下的 minio 与 mc（版本与线上一致）"
  cp -a /usr/local/bin/minio "${OFFLINE_DIR}/binaries/minio"
  cp -a /usr/local/bin/mc "${OFFLINE_DIR}/binaries/mc"
else
  # 版本刻意锁定（与线上一致）：MinIO 新版本移除了完整管理控制台
  MINIO_URL="https://dl.min.io/server/minio/release/linux-amd64/archive/minio.RELEASE.2025-04-22T22-12-26Z"
  MC_URL="https://dl.min.io/client/mc/release/linux-amd64/archive/mc.RELEASE.2025-08-13T08-35-41Z"
  log "下载 minio（约 118MB）"
  curl -fsSL -o "${OFFLINE_DIR}/binaries/minio" "${MINIO_URL}"
  log "下载 mc"
  curl -fsSL -o "${OFFLINE_DIR}/binaries/mc" "${MC_URL}"
fi
chmod 0755 "${OFFLINE_DIR}/binaries/minio" "${OFFLINE_DIR}/binaries/mc"
if [[ -x "${OFFLINE_DIR}/binaries/minio" ]]; then
  ver=$("${OFFLINE_DIR}/binaries/minio" --version 2>/dev/null | head -1 || echo "（无法在构建机上执行，跳过版本校验）")
  ok "minio：${ver}"
fi

echo "==== 4/5 生成包清单 ===="
{
  echo "# DMS 离线依赖清单（Debian 13 / amd64）"
  echo "# 生成时间：$(date '+%F %T')"
  echo "# 生成主机：$(hostname) （$(. /etc/os-release; echo "$PRETTY_NAME")）"
  echo
  echo "## 系统包（共 $(ls "${OFFLINE_DIR}/debs"/*.deb | wc -l) 个）"
  for f in "${OFFLINE_DIR}/debs"/*.deb; do
    printf '%s\t%s\n' "$(dpkg-deb -f "$f" Package)" "$(dpkg-deb -f "$f" Version)"
  done | sort
  echo
  echo "## 二进制"
  echo "minio	$("${OFFLINE_DIR}/binaries/minio" --version 2>/dev/null | head -1 | awk '{print $3}' || echo 'RELEASE.2025-04-22T22-12-26Z')"
  echo "mc	$("${OFFLINE_DIR}/binaries/mc" --version 2>/dev/null | head -1 | awk '{print $3}' || echo 'RELEASE.2025-08-13T08-35-41Z')"
} > "${OFFLINE_DIR}/packages.list"
ok "清单：${OFFLINE_DIR}/packages.list"

if [[ ${WITH_ONLYOFFICE} -eq 1 ]]; then
  echo "==== 5/5 准备 OnlyOffice 镜像（可选组件，约 3.3GB）===="
  mkdir -p "${ONLYOFFICE_DIR}"
  if [[ -n "${FROM_SERVER}" ]]; then
    log "从 ${FROM_SERVER} 复制镜像 tar（很慢，请耐心）"
    "${SCP[@]}" -q "${FROM_SERVER}:/root/onlyoffice_documentserver_latest.tar" "${ONLYOFFICE_DIR}/documentserver.tar"
  elif [[ -f /root/onlyoffice_documentserver_latest.tar ]]; then
    log "使用本机已有的镜像 tar /root/onlyoffice_documentserver_latest.tar"
    cp -a /root/onlyoffice_documentserver_latest.tar "${ONLYOFFICE_DIR}/documentserver.tar"
  elif docker image inspect onlyoffice/documentserver:latest >/dev/null 2>&1; then
    log "从本机 docker 导出镜像"
    docker save onlyoffice/documentserver:latest -o "${ONLYOFFICE_DIR}/documentserver.tar"
  else
    log "本机没有该镜像，改为 docker pull 后导出"
    docker pull onlyoffice/documentserver:latest
    docker save onlyoffice/documentserver:latest -o "${ONLYOFFICE_DIR}/documentserver.tar"
  fi
  ok "OnlyOffice 镜像：$(du -h "${ONLYOFFICE_DIR}/documentserver.tar" | cut -f1)"
  # Docker 自身的包也一起带上（离线装 OO 需要）
  log "补充 Docker 相关 .deb（同样按空状态解析，确保闭包完整）"
  download_closure "${OFFLINE_DIR}/debs" docker.io containerd >/dev/null
  python3 - "${OFFLINE_DIR}" <<'PY'
import gzip, hashlib, subprocess, sys, pathlib
root = pathlib.Path(sys.argv[1]); debs = sorted((root/'debs').glob('*.deb'))
entries = []
for d in debs:
    fields = {}
    for line in subprocess.run(['dpkg-deb','-f',str(d)], capture_output=True, text=True, check=True).stdout.splitlines():
        if ':' in line and not line.startswith(' '):
            k, v = line.split(':', 1); fields[k.strip()] = v.strip()
    fields['Filename'] = 'debs/' + d.name
    fields['Size'] = str(d.stat().st_size)
    fields['SHA256'] = hashlib.sha256(d.read_bytes()).hexdigest()
    order = ['Package','Version','Architecture','Maintainer','Installed-Size','Depends','Pre-Depends','Recommends','Suggests','Conflicts','Breaks','Replaces','Provides','Section','Priority','Homepage','Description','Filename','Size','SHA256']
    out = ['%s: %s' % (k, fields[k]) for k in order if k in fields]
    out += ['%s: %s' % (k, fields[k]) for k in fields if k not in order]
    entries.append('\n'.join(out))
(root/'Packages').write_text('\n\n'.join(entries) + '\n')
with gzip.open(root/'Packages.gz','wb') as f:
    f.write((root/'Packages').read_bytes())
print('    重新生成索引：%d 个包' % len(entries))
PY
fi

echo
echo "==== 依赖收集完成 ===="
du -sh "${OFFLINE_DIR}/debs" "${OFFLINE_DIR}/binaries" 2>/dev/null
[[ ${WITH_ONLYOFFICE} -eq 1 ]] && du -sh "${ONLYOFFICE_DIR}" 2>/dev/null
echo "接下来：./build-release.sh  生成可交付的发布包"

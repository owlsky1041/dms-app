#!/usr/bin/env bash
# =============================================================================
# 导入演示数据集
#
# 把 demo-data/files/ 下的目录结构与文件灌进 DMS：
#   一级目录 → 顶层文档区（仅内置超管可建，脚本用管理员账号登录）
#   更深层级 → 逐级创建子目录
#   文件     → tus 断点续传上传（与前端同一条链路，因此预览/审计/权限表现一致）
#
# 数据集内容：28 个文件，覆盖 docx/pdf/xlsx/csv/pptx/txt/md/html/jpg/png/mp4
#   —— 正好覆盖三条预览路径（图片视频原生、Office/PDF 走 OnlyOffice、文本类文本预览）。
#
# 用法：
#   ./import-demo-data.sh                        # 新建「演示文档库」文档区并灌入
#   ./import-demo-data.sh --zone-name 培训资料    # 自定义文档区名称
#   ./import-demo-data.sh --parent <目录ID>       # 不建新文档区，挂到已有目录下
#   ./import-demo-data.sh --dry-run               # 只列出将要做什么
# =============================================================================
set -Eeuo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SELF_DIR}/../demo-data/files"
BASE="http://127.0.0.1:8080"
CID="e5cd7e4891bf95d1d19206ce24a7b32e"
CRED_FILE="/root/.dms-credentials"
ZONE_NAME="演示文档库"
PARENT_ID=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --zone-name) ZONE_NAME="$2"; shift 2 ;;
    --parent)    PARENT_ID="$2"; shift 2 ;;
    --base)      BASE="$2"; shift 2 ;;
    --dry-run)   DRY_RUN=1; shift ;;
    -h|--help)   sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

log() { echo "  · $*"; }
ok()  { echo "  ✔ $*"; }
die() { echo "  ✘ $*" >&2; exit 1; }

[[ -d "${DATA_DIR}" ]] || die "找不到数据集目录 ${DATA_DIR}（发布包是否完整？）"
[[ -f "${CRED_FILE}" ]] || die "找不到 ${CRED_FILE}（需要管理员口令才能导入）"
# shellcheck disable=SC1090
. "${CRED_FILE}"
: "${ADMIN_USER:=admin}"
[[ -n "${ADMIN_PASSWORD:-}" ]] || die "${CRED_FILE} 里没有 ADMIN_PASSWORD"

TOKEN=""
api() { # api <METHOD> <PATH> [JSON]
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "${body}" ]]; then
    curl -fsS -X "${method}" "${BASE}${path}" -H 'Content-Type: application/json' \
      -H "clientid: ${CID}" ${TOKEN:+-H "Authorization: Bearer ${TOKEN}"} -d "${body}"
  else
    curl -fsS -X "${method}" "${BASE}${path}" \
      -H "clientid: ${CID}" ${TOKEN:+-H "Authorization: Bearer ${TOKEN}"}
  fi
}

# 取 JSON 里的字段：传点号路径（如 data.access_token）。
# 不要用 eval 拼 python 字符串——嵌套引号会被 shell 吃掉，表现为"明明有 token 却说登录失败"
json_get() {
  python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in sys.argv[1].split("."):
    if isinstance(d, list):
        d = d[int(k)]
    elif isinstance(d, dict):
        d = d.get(k)
    else:
        sys.exit(0)
    if d is None:
        sys.exit(0)
print(d if not isinstance(d, bool) else str(d).lower())
' "$1" 2>/dev/null || true
}

login() {
  local res
  res=$(curl -fsS -X POST "${BASE}/auth/login" -H 'Content-Type: application/json' -H "clientid: ${CID}" \
        -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\",\"clientId\":\"${CID}\",\"grantType\":\"password\"}")
  TOKEN=$(printf '%s' "${res}" | json_get data.access_token)
  [[ -n "${TOKEN}" ]] || die "管理员登录失败：$(printf '%s' "${res}" | head -c 160)"
}

# 创建目录，返回新目录 ID
create_folder() { # create_folder <parentId> <name>
  local parent="$1" name="$2" res
  res=$(api POST /api/doc/folders "{\"parentId\":${parent},\"name\":\"${name}\"}")
  printf '%s' "${res}" | json_get data.folderId
}

# tus 上传：建会话 → PATCH 字节 → complete
upload_file() { # upload_file <folderId> <localPath>
  local folder="$1" path="$2"
  local name size meta loc upid
  name="$(basename "${path}")"
  size="$(stat -c%s "${path}")"
  meta="filename $(printf '%s' "${name}" | base64 -w0),folderId $(printf '%s' "${folder}" | base64 -w0)"
  loc=$(curl -fsS -D - -o /dev/null -X POST "${BASE}/api/upload/tus" \
        -H "clientid: ${CID}" -H "Authorization: Bearer ${TOKEN}" \
        -H "Tus-Resumable: 1.0.0" -H "Upload-Length: ${size}" -H "Upload-Metadata: ${meta}" \
        | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}') || true
  [[ -n "${loc}" ]] || { echo "    ✘ ${name}: 建上传会话失败"; return 1; }
  # Location 可能是相对路径（/api/upload/tus/<id>）也可能是绝对地址，统一取路径部分
  local p="${loc}"
  if [[ "${loc}" == http* ]]; then
    p="/$(printf '%s' "${loc#*://}" | cut -d/ -f2-)"
  fi
  curl -fsS -o /dev/null -X PATCH "${BASE}${p}" \
    -H "clientid: ${CID}" -H "Authorization: Bearer ${TOKEN}" \
    -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" \
    -H "Content-Type: application/offset+octet-stream" --data-binary "@${path}"
  upid="$(printf '%s' "${p}" | sed 's:/*$::' | awk -F/ '{print $NF}')"
  curl -fsS -o /dev/null -X POST "${BASE}/api/upload/${upid}/complete" \
    -H "clientid: ${CID}" -H "Authorization: Bearer ${TOKEN}"
}

echo "==== DMS 演示数据集导入 ===="
echo "  数据集目录：${DATA_DIR}"
echo "  目标：$([[ -n "${PARENT_ID}" ]] && echo "已有目录 ID=${PARENT_ID}" || echo "新建顶层文档区「${ZONE_NAME}」")"
find "${DATA_DIR}" -type f | wc -l | xargs -I{} echo "  待导入文件：{} 个"
if [[ ${DRY_RUN} -eq 1 ]]; then
  echo
  find "${DATA_DIR}" -mindepth 1 -printf '  %P\n' | sort | sed 's/^/    /'
  echo
  echo "  （dry-run 结束，未做任何改动）"
  exit 0
fi

login
ok "管理员登录成功"

if [[ -n "${PARENT_ID}" ]]; then
  ROOT_ID="${PARENT_ID}"
else
  ROOT_ID="$(create_folder 0 "${ZONE_NAME}")"
  [[ -n "${ROOT_ID}" ]] || die "创建文档区「${ZONE_NAME}」失败（顶层文档区仅内置超管可建）"
  ok "已创建文档区「${ZONE_NAME}」（id=${ROOT_ID}）"
fi

# 逐级建目录再上传：用「相对路径 → 目录ID」的映射缓存，避免重复创建
# 关联数组的键不能用空串：${DIR_IDS[]} 会报 "bad array subscript"，所以根目录用 "." 作为键。
#
# 注意：这个函数**不能用 $(...) 调用**——命令替换会开子 shell，
# 函数里对 DIR_IDS 的写入会丢在子 shell 里，于是每个文件都会去重新建一遍目录，
# 第二个文件就撞上"同名文件夹已存在"（踩过）。所以结果通过全局变量 FOLDER_ID 返回。
declare -A DIR_IDS
DIR_IDS["."]="${ROOT_ID}"
FOLDER_ID=""

folder_for() { # folder_for <相对目录，根为 ".">；结果写入 FOLDER_ID
  local rel="${1:-.}"
  if [[ -n "${DIR_IDS[${rel}]:-}" ]]; then
    FOLDER_ID="${DIR_IDS[${rel}]}"
    return 0
  fi
  local parent_rel name id
  parent_rel="$(dirname "${rel}")"
  name="$(basename "${rel}")"
  folder_for "${parent_rel}"
  id="$(create_folder "${FOLDER_ID}" "${name}")"
  if [[ -z "${id}" ]]; then
    echo "  ✘ 创建目录失败：${rel}" >&2
    return 1
  fi
  DIR_IDS["${rel}"]="${id}"
  FOLDER_ID="${id}"
}

created_dirs=0; uploaded=0; failed=0
while IFS= read -r -d '' f; do
  rel="$(dirname "${f#"${DATA_DIR}"/}")"; [[ "${rel}" == "." ]] && rel=""
  if [[ -z "${rel}" ]]; then
    fid="${ROOT_ID}"
  else
    before="${#DIR_IDS[@]}"
    folder_for "${rel}" || continue
    fid="${FOLDER_ID}"
    [[ "${#DIR_IDS[@]}" -gt "${before}" ]] && created_dirs=$((created_dirs+1))
  fi
  if upload_file "${fid}" "${f}"; then
    uploaded=$((uploaded+1)); printf '  ↑ %s\n' "${rel:+${rel}/}$(basename "${f}")"
  else
    failed=$((failed+1)); printf '  ✘ 上传失败：%s\n' "${f}"
  fi
done < <(find "${DATA_DIR}" -type f -print0 | sort -z)

echo
ok "导入完成：上传 ${uploaded} 个文件，新建子目录 $((created_dirs)) 个$([[ ${failed} -gt 0 ]] && echo "，失败 ${failed} 个")"
cat <<EOF

  接下来可以：
    1) 浏览器打开 http://<服务器地址>/ → 文档区「${ZONE_NAME:+${ZONE_NAME}}」
    2) 逐个双击打开：PDF/Office 走 OnlyOffice；图片/视频原生播放；txt/md/csv 走文本预览
    3) 用右键「权限设置」把该文档区按需授给角色（只读/读写/完全控制/禁止访问）
EOF

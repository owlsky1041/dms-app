#!/bin/bash
# 模拟文件夹上传：带 relativePath 元数据，验证后端自动建目录
set -e
BASE="http://127.0.0.1"
C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
A="Authorization: Bearer $T"

# 生成测试文件
printf '文件夹上传测试 %s\n' "$(date)" > /tmp/folder-test.txt
FILE=/tmp/folder-test.txt
SIZE=$(stat -c%s "$FILE")
FB=$(printf "%s" "folder-test.txt" | base64)
# 模拟 webkitdirectory: 顶层"设计资料"/子"图纸"/文件
REL=$(printf "%s" "设计资料/图纸/folder-test.txt" | base64)

echo "== 上传到 folder 1，relativePath=设计资料/图纸/ =="
LOC=$(curl -s -D - -o /dev/null -X POST "$BASE/api/upload/tus" -H "$A" -H "clientid: $C" \
  -H "Tus-Resumable: 1.0.0" -H "Upload-Length: $SIZE" \
  -H "Upload-Metadata: filename $FB,folderId $(printf 1|base64),relativePath $REL" \
  | grep -i "^location:" | awk '{print $2}' | tr -d '\r')
echo "loc=$LOC"
curl -s -o /dev/null -w "PATCH: %{http_code}\n" -X PATCH "$BASE$LOC" -H "$A" -H "clientid: $C" \
  -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" -H "Content-Type: application/offset+octet-stream" \
  --data-binary @"$FILE"
UPLOAD_ID=$(basename "$LOC")
echo "complete:"
curl -s -X POST "$BASE/api/upload/$UPLOAD_ID/complete" -H "$A" -H "clientid: $C" -H "Content-Type: application/json"
echo ""

echo "== 验证自动创建的目录链 =="
su - postgres -c "psql -d dms -c \"SELECT folder_id, parent_id, folder_name, folder_path FROM doc_folder WHERE folder_name IN ('设计资料','图纸') OR folder_path LIKE '%/1/7/%' OR folder_path LIKE '%设计%' ORDER BY folder_id DESC LIMIT 5\"" 2>/dev/null || \
curl -s "$BASE/api/doc/folders/children?parentId=1" -H "$A" -H "clientid: $C" | python3 -m json.tool 2>/dev/null | head -20

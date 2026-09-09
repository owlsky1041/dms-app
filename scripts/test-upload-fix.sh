#!/bin/bash
# 完整模拟浏览器上传（验证 ownerKey 修复）
set -e
BASE="http://127.0.0.1"
C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
A="Authorization: Bearer $T"

# 生成测试文件
echo "测试文档 PDF 生成 $(date)" > /tmp/upload-test.txt
libreoffice --headless --convert-to pdf --outdir /tmp /tmp/upload-test.txt >/dev/null 2>&1 || true
FILE=/tmp/upload-test.pdf
[ -f "$FILE" ] || FILE=/tmp/upload-test.txt
SIZE=$(stat -c%s "$FILE")
FNAME=$(basename "$FILE")
FB=$(printf "%s" "$FNAME" | base64)

echo "文件: $FILE ($SIZE B)"

# 创建 tus 会话
LOC=$(curl -s -D - -o /dev/null -X POST "$BASE/api/upload/tus" \
  -H "$A" -H "clientid: $C" -H "Tus-Resumable: 1.0.0" -H "Upload-Length: $SIZE" \
  -H "Upload-Metadata: filename $FB,folderId $(printf 1|base64)" \
  | grep -i "^location:" | awk '{print $2}' | tr -d '\r')
echo "location: $LOC"

# PATCH 分块
RC=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE$LOC" -H "$A" -H "clientid: $C" \
  -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" -H "Content-Type: application/offset+octet-stream" \
  --data-binary @"$FILE")
echo "PATCH: $RC"

# complete
UPLOAD_ID=$(basename "$LOC")
echo "complete result:"
curl -s -X POST "$BASE/api/upload/$UPLOAD_ID/complete" -H "$A" -H "clientid: $C" -H "Content-Type: application/json"
echo ""

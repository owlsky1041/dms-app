#!/bin/bash
# tus 上传全链路测试（指定文件）
set -e
BASE="http://127.0.0.1"
CLIENT_ID="e5cd7e4891bf95d1d19206ce24a7b32e"
FILE_PATH="${1:-/tmp/test-report.pdf}"

# 1. 登录
LOGIN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" \
  -H "clientid: $CLIENT_ID" \
  -d '{"username":"admin","password":"admin123","clientId":"e5cd7e4891bf95d1d19206ce24a7b32e","grantType":"password"}')
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['access_token'])")
AUTH="Authorization: Bearer $TOKEN"
echo "上传文件: $FILE_PATH"

SIZE=$(stat -c%s "$FILE_PATH")
FNAME=$(basename "$FILE_PATH")
FILENAME_B64=$(printf "%s" "$FNAME" | base64)
FOLDER_ID_B64=$(printf "1" | base64)
USER_ID_B64=$(printf "1761100000000000001" | base64)
EXT="${FNAME##*.}"

# 2. 创建
CREATION=$(curl -s -i -X POST "$BASE/api/upload/tus" \
  -H "$AUTH" -H "clientid: $CLIENT_ID" \
  -H "Tus-Resumable: 1.0.0" \
  -H "Upload-Length: $SIZE" \
  -H "Upload-Metadata: filename $FILENAME_B64,folderId $FOLDER_ID_B64,userId $USER_ID_B64,fileType application/octet-stream" \
  -w "\n%{http_code}" 2>&1)
CREATION_CODE=$(echo "$CREATION" | tail -1)
LOCATION=$(echo "$CREATION" | grep -i "^location:" | awk '{print $2}' | tr -d '\r')
echo "创建: $CREATION_CODE  $LOCATION"
[ "$CREATION_CODE" != "201" ] && { echo "$CREATION" | head -5; exit 1; }

# 3. PATCH 分块
PATCH_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE$LOCATION" \
  -H "$AUTH" -H "clientid: $CLIENT_ID" \
  -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" \
  -H "Content-Type: application/offset+octet-stream" \
  --data-binary @"$FILE_PATH" 2>&1)
echo "PATCH: $PATCH_CODE"

# 4. complete
UPLOAD_ID=$(basename "$LOCATION")
COMPLETE=$(curl -s -X POST "$BASE/api/upload/$UPLOAD_ID/complete" \
  -H "$AUTH" -H "clientid: $CLIENT_ID" -H "Content-Type: application/json")
echo "complete: $COMPLETE"

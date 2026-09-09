#!/bin/bash
# tus 上传全链路测试（模拟 Uppy 客户端）
set -e
BASE="http://127.0.0.1"
CLIENT_ID="e5cd7e4891bf95d1d19206ce24a7b32e"

# 1. 登录
echo "=== 1. 登录 ==="
LOGIN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" \
  -H "clientid: $CLIENT_ID" \
  -d '{"username":"admin","password":"admin123","clientId":"e5cd7e4891bf95d1d19206ce24a7b32e","grantType":"password"}')
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['access_token'])")
echo "token ok (${#TOKEN} chars)"
AUTH="Authorization: Bearer $TOKEN"

# 2. 创建 tus 上传会话
echo "=== 2. 创建上传会话 ==="
TEST_FILE=/tmp/dms-upload-test.txt
echo "DMS 上传全链路测试文件 $(date)" > "$TEST_FILE"
SIZE=$(stat -c%s "$TEST_FILE")
echo "文件: $TEST_FILE, 大小: $SIZE"

FILENAME_B64=$(printf "dms-upload-test.txt" | base64)
FOLDER_ID_B64=$(printf "1" | base64)
USER_ID_B64=$(printf "1761100000000000001" | base64)

CREATION=$(curl -s -i -X POST "$BASE/api/upload/tus" \
  -H "$AUTH" \
  -H "clientid: $CLIENT_ID" \
  -H "Tus-Resumable: 1.0.0" \
  -H "Upload-Length: $SIZE" \
  -H "Upload-Metadata: filename $FILENAME_B64,folderId $FOLDER_ID_B64,userId $USER_ID_B64,fileType text/plain" \
  -w "\n%{http_code}" 2>&1)
CREATION_CODE=$(echo "$CREATION" | tail -1)
LOCATION=$(echo "$CREATION" | grep -i "^location:" | awk '{print $2}' | tr -d '\r')
echo "创建响应码: $CREATION_CODE"
echo "Location: $LOCATION"
if [ "$CREATION_CODE" != "201" ]; then
  echo "!!! 创建失败"
  echo "$CREATION" | head -20
  exit 1
fi

# 3. 传分块
echo "=== 3. 上传分块 (PATCH) ==="
PATCH_RESP=$(curl -s -i -X PATCH "$BASE$LOCATION" \
  -H "$AUTH" \
  -H "clientid: $CLIENT_ID" \
  -H "Tus-Resumable: 1.0.0" \
  -H "Upload-Offset: 0" \
  -H "Content-Type: application/offset+octet-stream" \
  --data-binary @"$TEST_FILE" \
  -w "\n%{http_code}" 2>&1)
PATCH_CODE=$(echo "$PATCH_RESP" | tail -1)
echo "PATCH 响应码: $PATCH_CODE"
echo "$PATCH_RESP" | grep -iE "^Upload-Offset|^Tus-Resumable" | head -3

# 4. HEAD 查询
echo "=== 4. 查询进度 (HEAD) ==="
HEAD_RESP=$(curl -s -I "$BASE$LOCATION" \
  -H "$AUTH" \
  -H "clientid: $CLIENT_ID" \
  -H "Tus-Resumable: 1.0.0" 2>&1)
echo "$HEAD_RESP" | grep -iE "^Upload-Offset|^Upload-Length|HTTP" | head -3

# 5. 完成回调
echo "=== 5. 完成回调 ==="
UPLOAD_ID=$(basename "$LOCATION")
echo "uploadId: $UPLOAD_ID"
COMPLETE=$(curl -s -X POST "$BASE/api/upload/$UPLOAD_ID/complete" \
  -H "$AUTH" \
  -H "clientid: $CLIENT_ID" \
  -H "Content-Type: application/json" 2>&1)
echo "complete: $COMPLETE"

echo "=== 完成 ==="

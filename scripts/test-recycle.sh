#!/bin/bash
# 回收站全流程测试
BASE="http://127.0.0.1"
CLIENT_ID="e5cd7e4891bf95d1d19206ce24a7b32e"
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" -H "clientid: $CLIENT_ID" \
  -d '{"username":"admin","password":"admin123","clientId":"e5cd7e4891bf95d1d19206ce24a7b32e","grantType":"password"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['access_token'])")
AUTH="Authorization: Bearer $TOKEN"
H="clientid: $CLIENT_ID"

echo "=== 1. 软删文件 6 (dms-upload-test.txt) ==="
curl -s -X DELETE "http://127.0.0.1/api/doc/files/6" -H "$AUTH" -H "$H" | head -c 100
echo ""
echo "=== 2. 回收站列表 ==="
curl -s "http://127.0.0.1/api/doc/recycle/list" -H "$AUTH" -H "$H" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', {})
fs = data.get('files', [])
print('files in recycle:', [(f['fileId'], f['fileName']) for f in fs])
"
echo "=== 3. 恢复文件 6 ==="
curl -s -X POST "http://127.0.0.1/api/doc/recycle/files/6/restore" -H "$AUTH" -H "$H" | head -c 100
echo ""
echo "=== 4. 再删文件 6 + 永久删文件 5 ==="
curl -s -X DELETE "http://127.0.0.1/api/doc/files/6" -H "$AUTH" -H "$H" -o /dev/null -w "delete6: %{http_code}\n"
curl -s -X DELETE "http://127.0.0.1/api/doc/files/5" -H "$AUTH" -H "$H" -o /dev/null -w "delete5: %{http_code}\n"
echo "=== 5. 永久删文件 5 ==="
curl -s -X DELETE "http://127.0.0.1/api/doc/recycle/files/5" -H "$AUTH" -H "$H" | head -c 100
echo ""
echo "=== 6. 最终回收站 ==="
curl -s "http://127.0.0.1/api/doc/recycle/list" -H "$AUTH" -H "$H" | python3 -c "
import sys, json
d = json.load(sys.stdin)
fs = d.get('data', {}).get('files', [])
print('files now in recycle:', [(f['fileId'], f['fileName']) for f in fs])
"
echo "=== 完成 ==="

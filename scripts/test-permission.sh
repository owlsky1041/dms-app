#!/bin/bash
# 权限 API 测试
BASE="http://127.0.0.1"
CLIENT_ID="e5cd7e4891bf95d1d19206ce24a7b32e"
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" -H "clientid: $CLIENT_ID" \
  -d '{"username":"admin","password":"admin123","clientId":"e5cd7e4891bf95d1d19206ce24a7b32e","grantType":"password"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['access_token'])")
AUTH="Authorization: Bearer $TOKEN"

echo "=== 1. 查角色列表(拿 superadmin 角色id) ==="
ROLE_ID=$(curl -s "http://127.0.0.1/system/role/list?pageNum=1&pageSize=10" -H "$AUTH" -H "clientid: $CLIENT_ID" \
  | python3 -c "
import sys,json
d = json.load(sys.stdin)
rows = d.get('data', {}).get('rows', [])
for r in rows:
    print(r.get('roleId'))
    break
")
echo "superadmin roleId=$ROLE_ID"

echo "=== 2. 给文件夹1授权 role:$ROLE_ID 可见+预览+下载(1+2+8=11) ==="
curl -s -X POST "http://127.0.0.1/api/perm/folders/1/grant" -H "$AUTH" -H "clientid: $CLIENT_ID" \
  -H "Content-Type: application/json" \
  -d "{\"subjectType\":\"role\",\"subjectId\":$ROLE_ID,\"permFlags\":11,\"inheritToChildren\":true}" | head -c 150
echo ""
echo "=== 3. 查当前用户对文件夹1的有效权限位 ==="
curl -s "http://127.0.0.1/api/perm/check?resourceType=folder&resourceId=1" -H "$AUTH" -H "clientid: $CLIENT_ID" | head -c 150
echo ""
echo "=== 4. 查权限位常量 ==="
curl -s "http://127.0.0.1/api/perm/flags" -H "$AUTH" -H "clientid: $CLIENT_ID" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('data',[])), 'flags')"
echo "=== 5. 文件8授权 user admin 完全控制 ==="
curl -s -X POST "http://127.0.0.1/api/perm/files/8/grant" -H "$AUTH" -H "clientid: $CLIENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"subjectType":"user","subjectId":1761100000000000001,"permFlags":255}' | head -c 150
echo ""
echo "=== 完成 ==="

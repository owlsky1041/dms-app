#!/bin/bash
# 测试删除根目录下的文件夹
B="http://127.0.0.1"
C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$B/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
H="Authorization: Bearer $T"

echo "=== 根目录 children ==="
curl -s "$B/api/doc/folders/children?parentId=1" -H "$H" -H "clientid: $C" | python3 -c "
import sys,json
rows = json.load(sys.stdin).get('data') or []
for r in rows: print(r['folderId'], r['folderName'])
"
echo "=== 删除 folder 9 (测试A) ==="
curl -s -X DELETE "$B/api/doc/folders/9" -H "$H" -H "clientid: $C" -w "\nHTTP:%{http_code}" | head -c 300
echo ""
echo "=== 删除 folder 10 (设计资料, 含子目录) ==="
curl -s -X DELETE "$B/api/doc/folders/10" -H "$H" -H "clientid: $C" -w "\nHTTP:%{http_code}" | head -c 300
echo ""
echo "=== 再列根目录 ==="
curl -s "$B/api/doc/folders/children?parentId=1" -H "$H" -H "clientid: $C" | python3 -c "
import sys,json
rows = json.load(sys.stdin).get('data') or []
print('remaining:', [(r['folderId'], r['folderName']) for r in rows])
"

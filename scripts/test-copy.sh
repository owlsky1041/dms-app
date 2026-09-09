#!/bin/bash
# 复制/批量移动 API 测试
BASE="http://127.0.0.1"
C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
H="Authorization: Bearer $T"

echo "=== 当前文件列表 (folderId=1) ==="
curl -s "$BASE/api/doc/files?folderId=1&page=1&size=50" -H "$H" -H "clientid: $C" -o /tmp/fl.json
python3 -c "
import json
d = json.load(open('/tmp/fl.json'))
rows = (d.get('data') or {}).get('records') or []
for r in rows: print(r['fileId'], r['fileName'])
print('total:', len(rows))
"

echo "=== 创建目标文件夹 移动测试 ==="
curl -s -X POST "$BASE/api/doc/folders" -H "$H" -H "clientid: $C" -H "Content-Type: application/json" \
  -d '{"parentId":1,"name":"移动测试目标"}' -o /tmp/fld.json
FID=$(python3 -c "import json;print(json.load(open('/tmp/fld.json'))['data']['folderId'])")
echo "targetFolderId=$FID"

echo "=== 复制文件 8 到目标文件夹 ==="
curl -s -X POST "$BASE/api/doc/files/8/copy?targetFolderId=$FID" -H "$H" -H "clientid: $C" | head -c 120
echo ""

echo "=== 验证目标文件夹内容 ==="
curl -s "$BASE/api/doc/files?folderId=$FID&page=1&size=10" -H "$H" -H "clientid: $C" | python3 -c "
import sys,json
d=json.load(sys.stdin)
rows=(d.get('data') or {}).get('records') or []
print('copied files:', [(r['fileId'], r['fileName']) for r in rows])
"

echo "=== 批量移动 (cut 粘贴模拟): 把复制出来的移动到 folderId=1 ==="
COPY_ID=$(curl -s "$BASE/api/doc/files?folderId=$FID&page=1&size=10" -H "$H" -H "clientid: $C" | python3 -c "import sys,json;d=json.load(sys.stdin);r=(d.get('data') or {}).get('records') or [];print(r[0]['fileId'] if r else 0)")
echo "copyId=$COPY_ID"
curl -s -X PUT "$BASE/api/doc/files/batch-move" -H "$H" -H "clientid: $C" -H "Content-Type: application/json" \
  -d "{\"fileIds\":[$COPY_ID],\"targetFolderId\":1}" | head -c 120
echo ""
echo "=== 清理测试文件夹 ==="
curl -s -X DELETE "$BASE/api/doc/folders/$FID" -H "$H" -H "clientid: $C" | head -c 80
echo ""
echo "=== 完成 ==="

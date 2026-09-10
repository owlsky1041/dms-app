#!/bin/bash
# 验证删除含深层子目录的根下文件夹(浏览器等效)
B="http://127.0.0.1"; C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$B/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
A="Authorization: Bearer $T"
echo "== 删除根下 folder 12 (含 14->17 子树) =="
curl -s -X DELETE "$B/api/doc/folders/12" -H "$A" -H "clientid: $C" -w " HTTP:%{http_code}" | head -c 160
echo ""
echo "== 验证 12/14/17 均已软删 =="
su - postgres -c "psql -d dms -tAc \"SELECT folder_id, folder_name, (deleted_at IS NOT NULL) AS del FROM doc_folder WHERE folder_id IN (12,14,17) ORDER BY folder_id\""
echo "== 根目录剩余 =="
curl -s "$B/api/doc/folders/children?parentId=1" -H "$A" -H "clientid: $C" | python3 -c "
import sys,json
rows=json.load(sys.stdin).get('data') or []
print([(r['folderId'], r['folderName']) for r in rows])
"

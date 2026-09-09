#!/bin/bash
# 验证删除文件夹会连带软删其中文件
B="http://127.0.0.1"; C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$B/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
A="Authorization: Bearer $T"
FID=16
echo "== 向 folder $FID 上传一个文件 =="
printf 'folder-delete-test %s\n' "$(date)" > /tmp/fd.txt
S=$(stat -c%s /tmp/fd.txt)
LOC=$(curl -s -D - -o /dev/null -X POST "$B/api/upload/tus" -H "$A" -H "clientid: $C" -H "Tus-Resumable: 1.0.0" -H "Upload-Length: $S" \
  -H "Upload-Metadata: filename $(printf fd.txt|base64),folderId $(printf "$FID"|base64)" | grep -i '^location:' | awk '{print $2}' | tr -d '\r')
curl -s -o /dev/null -w "PATCH:%{http_code}\n" -X PATCH "$B$LOC" -H "$A" -H "clientid: $C" -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" -H "Content-Type: application/offset+octet-stream" --data-binary @/tmp/fd.txt
UID2=$(basename "$LOC")
echo "complete: $(curl -s -X POST "$B/api/upload/$UID2/complete" -H "$A" -H "clientid: $C" | head -c 80)"
sleep 3
echo "== folder 16 中文件数 =="
su - postgres -c "psql -d dms -tAc \"SELECT count(*) FROM doc_file WHERE folder_id=$FID AND deleted_at IS NULL\""
echo "== 删除 folder 16 =="
curl -s -X DELETE "$B/api/doc/folders/$FID" -H "$A" -H "clientid: $C" | head -c 100
echo ""
sleep 1
echo "== 删除后：folder 16 应软删，其中文件应软删 =="
su - postgres -c "psql -d dms -tAc \"SELECT (SELECT count(*) FROM doc_folder WHERE folder_id=$FID AND deleted_at IS NOT NULL) AS folder_deleted, (SELECT count(*) FROM doc_file WHERE folder_id=$FID AND deleted_at IS NOT NULL) AS files_deleted\""

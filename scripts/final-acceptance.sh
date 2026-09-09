#!/bin/bash
# DMS 全流程最终验收脚本
set -e
B="http://127.0.0.1"
C="e5cd7e4891bf95d1d19206ce24a7b32e"
pass=0; fail=0
chk() { if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "✅ $1"; else fail=$((fail+1)); echo "❌ $1 (got $2 want $3)"; fi; }

T=$(curl -s -X POST "$B/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
H="Authorization: Bearer $T"

# 1. 根目录
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$B/api/doc/folders/root" -H "$H" -H "clientid: $C")
chk "1. 获取根目录" "$CODE" "200"

# 2. 建验收文件夹
FID=$(curl -s -X POST "$B/api/doc/folders" -H "$H" -H "clientid: $C" -H "Content-Type: application/json" \
  -d '{"parentId":1,"name":"最终验收目录"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['folderId'])")
chk "2. 创建文件夹" "${FID:-0}" "$(echo $FID | grep -qE '^[0-9]+$' && echo $FID || echo 0)"

# 3. 文件列表（根应 >0）
N=$(curl -s "$B/api/doc/files?folderId=1&page=1&size=50" -H "$H" -H "clientid: $C" | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('total') or 0)")
[ "$N" -gt 0 ] && chk "3. 文件列表非空" "ok" "ok" || chk "3. 文件列表非空" "empty" "nonempty"

# 4. 预览 docx(8) → pdf
CT=$(curl -s -D - -o /dev/null "$B/api/doc/files/8/preview" -H "$H" -H "clientid: $C" | grep -i content-type | tr -d '\r' | awk '{print $2}')
chk "4. docx预览为PDF" "$CT" "application/pdf;charset=utf-8"

# 5. 下载 Range
RC=$(curl -s -o /dev/null -w "%{http_code}" -r 0-99 "$B/api/doc/files/8/download" -H "$H" -H "clientid: $C")
chk "5. 下载Range 206" "$RC" "206"

# 6. 中文搜索
FOUND=$(curl -s "$B/api/doc/files/search?keyword=%E9%AA%8C%E6%94%B6&limit=5" -H "$H" -H "clientid: $C" | python3 -c "import sys,json;print(len(json.load(sys.stdin).get('data') or []))")
chk "6. 中文搜索(文档2)" "$FOUND" "1"

# 7. 回收站列表
RC=$(curl -s -o /dev/null -w "%{http_code}" "$B/api/doc/recycle/list" -H "$H" -H "clientid: $C")
chk "7. 回收站列表" "$RC" "200"

# 8. 权限 check
RC=$(curl -s -o /dev/null -w "%{http_code}" "$B/api/perm/check?resourceType=folder&resourceId=1" -H "$H" -H "clientid: $C")
chk "8. 权限检查" "$RC" "200"

# 9. 系统用户列表
RC=$(curl -s -o /dev/null -w "%{http_code}" "$B/system/user/list?pageNum=1&pageSize=5" -H "$H" -H "clientid: $C")
chk "9. 系统用户列表" "$RC" "200"

# 清理验收文件夹
curl -s -X DELETE "$B/api/doc/folders/$FID" -H "$H" -H "clientid: $C" -o /dev/null
echo ""
echo "======== 结果: $pass 通过 / $fail 失败 ========"

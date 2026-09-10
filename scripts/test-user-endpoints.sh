#!/bin/bash
# 验证 RuoYi 用户管理相关端点（RuoYi 6.0 真实路径）
B="http://127.0.0.1"; C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$B/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
H="Authorization: Bearer $T"
t() { printf "%-42s" "$1"; shift; curl -s -o /tmp/r.json -w "%{http_code}" "$@"; echo -n " | "; head -c 90 /tmp/r.json; echo ""; }

UID_ADMIN=1761100000000000001
echo "=== 端点探测 ==="
t "GET profile(个人资料)" "$B/system/user/profile" -H "$H" -H "clientid: $C"
t "GET authRole/{uid}(角色回显)" "$B/system/user/authRole/$UID_ADMIN" -H "$H" -H "clientid: $C"
t "PUT changeStatus" -X PUT "$B/system/user/changeStatus" -H "$H" -H "clientid: $C" -H "Content-Type: application/json" -d "{\"userId\":$UID_ADMIN,\"status\":\"0\"}"
t "PUT resetPwd" -X PUT "$B/system/user/resetPwd" -H "$H" -H "clientid: $C" -H "Content-Type: application/json" -d "{\"userId\":$UID_ADMIN,\"password\":\"admin123\"}"

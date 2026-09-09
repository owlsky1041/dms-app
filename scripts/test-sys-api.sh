#!/bin/bash
# 系统 API 写入路径测试
B=http://127.0.0.1
C="e5cd7e4891bf95d1d19206ce24a7b32e"
T=$(curl -s -X POST "$B/auth/login" -H "Content-Type: application/json" -H "clientid: $C" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"clientId\":\"$C\",\"grantType\":\"password\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
H="Authorization: Bearer $T"

echo "=== user/list ==="
curl -s "$B/system/user/list?pageNum=1&pageSize=3" -H "$H" -H "clientid: $C" -o /tmp/u.json
python3 -c "
import json
d = json.load(open('/tmp/u.json'))
rows = (d.get('data') or {}).get('rows') or []
print(len(rows), 'rows')
if rows: print('keys:', list(rows[0].keys())[:14])
"
echo "=== config/list ==="
curl -s "$B/system/config/list?pageNum=1&pageSize=50" -H "$H" -H "clientid: $C" -o /tmp/c.json
python3 -c "
import json
d = json.load(open('/tmp/c.json'))
rows = (d.get('data') or {}).get('rows') or []
print('total:', (d.get('data') or {}).get('total'))
target = [r for r in rows if r.get('configKey') == 'dms.minio.bucket']
print('found dms.minio.bucket:', bool(target))
"
echo "=== PUT config (更新 dms.minio.bucket) ==="
CID=$(python3 -c "
import json
d = json.load(open('/tmp/c.json'))
rows = (d.get('data') or {}).get('rows') or []
print(next((r['configId'] for r in rows if r.get('configKey')=='dms.minio.bucket'), 0))
")
curl -s -X PUT "$B/system/config" -H "$H" -H "clientid: $C" -H "Content-Type: application/json" \
  -d "{\"configId\":$CID,\"configName\":\"MinIO 桶名\",\"configKey\":\"dms.minio.bucket\",\"configValue\":\"dms-files\",\"remark\":\"DMS 文件桶\"}" | head -c 150
echo ""
echo "=== 完成 ==="

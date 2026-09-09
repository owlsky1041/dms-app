#!/bin/bash
#
# DMS 应用部署脚本（Debian 13.5）
#
# 假设 01-init-debian13.sh 已运行：
#   - PostgreSQL 16 已启动，dms 数据库已创建
#   - Redis 已启动
#   - MinIO 已启动
#   - Nginx 已安装
#   - LibreOffice 已安装
#
# 使用方式：
#   1. 上传 jar / dist / 配置到 /opt/dms/
#   2. ssh root@<server> 'bash /opt/dms/02-deploy-dms.sh'

set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
log() { echo -e "${GREEN}[$(date +'%H:%M:%S')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

DMS_HOME=/opt/dms
JAR_FILE=$DMS_HOME/dms-app.jar
DIST_DIR=$DMS_HOME/dist
CONF_FILE=$DMS_HOME/application-dev.yml

# ===========================================
# 1. 准备目录
# ===========================================
log "1. 准备 DMS 目录..."
mkdir -p $DMS_HOME/{logs,nginx-conf,systemd,sql}

# ===========================================
# 2. systemd unit
# ===========================================
log "2. 安装 systemd 服务..."

if [ ! -f $JAR_FILE ]; then
    warn "未找到 jar：$JAR_FILE"
    warn "请先上传：scp dms-app.jar root@<server>:/opt/dms/"
    exit 1
fi

cat > /etc/systemd/system/dms-app.service <<EOF
[Unit]
Description=DMS Application
After=postgresql.service redis-server.service minio.service
Wants=postgresql.service redis-server.service minio.service

[Service]
Type=simple
User=root
WorkingDirectory=$DMS_HOME
Environment="JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64"
Environment="SPRING_PROFILES_ACTIVE=dev"
ExecStart=/usr/bin/java \\
    -Xms512m -Xmx2g \\
    -XX:+UseG1GC \\
    -Dfile.encoding=UTF-8 \\
    --enable-native-access=ALL-UNNAMED \\
    -jar $JAR_FILE \\
    --spring.config.location=$CONF_FILE
Restart=always
RestartSec=10
StandardOutput=append:$DMS_HOME/logs/stdout.log
StandardError=append:$DMS_HOME/logs/stderr.log

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable dms-app

# ===========================================
# 3. Nginx 配置
# ===========================================
log "3. 配置 Nginx..."

if [ ! -d $DIST_DIR ]; then
    warn "未找到前端 dist：$DIST_DIR"
    warn "请先上传：scp -r dms-app-frontend/dist root@<server>:/opt/dms/"
fi

cat > /etc/nginx/sites-available/dms <<EOF
server {
    listen 80;
    server_name _;

    # 前端静态文件
    root $DIST_DIR;
    index index.html;

    # SPA fallback
    location / {
        try_files \$uri \$uri/ /index.html;
    }

    # 后端 API 反向代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        # tus 断点续传需要大 body
        client_max_body_size 0;
        proxy_request_buffering off;
        proxy_buffering off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    # PDF.js worker
    location ~* \.worker\.min\.(js|mjs)$ {
        root $DIST_DIR;
        expires 7d;
    }

    # 上传下载大文件，禁用缓冲
    location /api/doc/files/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_buffering off;
        proxy_request_buffering off;
    }

    access_log /var/log/nginx/dms-access.log;
    error_log /var/log/nginx/dms-error.log;
}
EOF

ln -sf /etc/nginx/sites-available/dms /etc/nginx/sites-enabled/dms
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

# ===========================================
# 4. MinIO 桶初始化
# ===========================================
log "4. 初始化 MinIO 桶..."

# 等待 MinIO 启动
for i in 1 2 3 4 5; do
    if curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

MC=/usr/local/bin/mc
if [ ! -f $MC ]; then
    wget -q https://dl.min.io/client/mc/release/linux-amd64/mc -O $MC
    chmod +x $MC
fi

$MC alias set local http://127.0.0.1:9000 minioadmin minioadmin123
$MC mb -p local/dms-files 2>/dev/null || warn "桶 dms-files 已存在"
$MC anonymous set download local/dms-files 2>/dev/null || true

# ===========================================
# 5. 数据库 Flyway 初始化
# ===========================================
log "5. 初始化数据库..."

# 第一次启动 dms-app 时会自动跑 Flyway
# 但 sys_user 等基础表需要初始化数据
if [ -f $DMS_HOME/sql/seed.sql ]; then
    sudo -u postgres psql -d dms -f $DMS_HOME/sql/seed.sql || true
fi

# ===========================================
# 6. 启动服务
# ===========================================
log "6. 启动 DMS..."
systemctl start dms-app
sleep 10

# 健康检查
if curl -fsS http://127.0.0.1:8080/api/upload/health >/dev/null 2>&1; then
    log "✅ DMS 启动成功！"
else
    warn "DMS 启动后健康检查失败，查看日志：journalctl -u dms-app -n 100"
fi

# ===========================================
# 7. 完成
# ===========================================
echo ""
log "✅ DMS 部署完成（Debian 13.5）"
echo ""
echo "访问："
echo "  - DMS 应用：http://<server-ip>/"
echo "  - 默认账号：admin / admin123"
echo "  - MinIO 控制台：http://<server-ip>:9001/  (minioadmin / minioadmin123)"
echo ""
echo "后续管理："
echo "  - systemctl status dms-app    # 查看应用状态"
echo "  - systemctl restart dms-app   # 重启"
echo "  - journalctl -u dms-app -f    # 实时日志"
echo "  - tail -f /opt/dms/logs/stdout.log"

#!/bin/bash
#
# DMS 服务器环境初始化脚本（Debian 13.5）
#
# 用途：在裸 Debian 13.5 服务器上一次性安装所有中间件 + DMS 服务
# 适用：生产环境、本地开发服务器、CI 测试机
#
# 使用方式：
#   scp 01-init-debian13.sh root@<server>:/tmp/
#   ssh root@<server> 'bash /tmp/01-init-debian13.sh'
#
# 镜像源：默认使用国内清华源（实际部署时按公司要求改）

set -euo pipefail

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date +'%H:%M:%S')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

# ===========================================
# 1. 基础环境
# ===========================================
log "1. 基础工具..."
apt-get update -y
apt-get install -y --no-install-recommends \
    curl wget vim nano git \
    apt-transport-https ca-certificates gnupg \
    sudo ufw \
    unzip zip tar \
    net-tools dnsutils \
    openjdk-21-jdk-headless

# ===========================================
# 2. PostgreSQL 16（Debian 13 默认源）
# ===========================================
log "2. PostgreSQL 16..."
apt-get install -y postgresql-16 postgresql-client-16
systemctl enable postgresql
systemctl start postgresql

# 配置 dms 数据库
sudo -u postgres psql <<EOF
CREATE USER dms WITH PASSWORD 'dms_password';
CREATE DATABASE dms OWNER dms ENCODING 'UTF8';
GRANT ALL PRIVILEGES ON DATABASE dms TO dms;
\c dms
GRANT ALL ON SCHEMA public TO dms;
EOF

# 允许本地无密码连接（生产建议改密码）
PG_HBA=/etc/postgresql/16/main/pg_hba.conf
sed -i 's/^local\s\+all\s\+all\s\+peer/local all all md5/' $PG_HBA
systemctl reload postgresql

# ===========================================
# 3. Redis 7
# ===========================================
log "3. Redis..."
apt-get install -y redis-server
systemctl enable redis-server
systemctl start redis-server

# 开启 keyspace notifications（缓存过期事件，可选）
sed -i 's/^notify-keyspace-events.*/notify-keyspace-events Ex/' /etc/redis/redis.conf || \
    echo "notify-keyspace-events Ex" >> /etc/redis/redis.conf
systemctl restart redis-server

# ===========================================
# 4. Nginx 1.26
# ===========================================
log "4. Nginx..."
apt-get install -y nginx
systemctl enable nginx

# ===========================================
# 5. LibreOffice（Office → PDF 转换）
# ===========================================
log "5. LibreOffice..."
apt-get install -y --no-install-recommends \
    libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress \
    libreoffice-script-provider-python \
    fonts-noto-cjk fonts-noto-cjk-extra

# ===========================================
# 6. MinIO（对象存储）
# ===========================================
log "6. MinIO Server..."
mkdir -p /opt/minio/data
useradd -r -s /sbin/nologin minio-user 2>/dev/null || true
chown -R minio-user:minio-user /opt/minio

# 下载最新 MinIO（Debian 13 是 amd64）
wget -q https://dl.min.io/server/minio/release/linux-amd64/minio -O /usr/local/bin/minio
chmod +x /usr/local/bin/minio

# MinIO 环境配置
cat > /etc/default/minio <<EOF
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin123
MINIO_VOLUMES=/opt/minio/data
MINIO_OPTS="--console-address :9001"
EOF

# systemd unit
cat > /etc/systemd/system/minio.service <<EOF
[Unit]
Description=MinIO
After=network.target

[Service]
User=minio-user
Group=minio-user
EnvironmentFile=/etc/default/minio
ExecStart=/usr/local/bin/minio server \$MINIO_VOLUMES \$MINIO_OPTS
Restart=always
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable minio
systemctl start minio

# ===========================================
# 7. 创建 dms 运行时目录
# ===========================================
log "7. DMS 运行时目录..."
mkdir -p /var/dms/{tus,uploads,logs}
chown -R :dms /var/dms 2>/dev/null || true
chmod -R 775 /var/dms

# ===========================================
# 8. 防火墙
# ===========================================
log "8. 防火墙..."
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 9000/tcp   # MinIO API（仅内网）
ufw allow 9001/tcp   # MinIO Console（仅内网）
ufw --force enable

# ===========================================
# 9. 完成
# ===========================================
log "✅ DMS 环境初始化完成（Debian 13.5）"
echo ""
echo "接下来："
echo "  1. 上传 dms-app.jar：scp dms-app/build/libs/dms-app.jar root@<server>:/opt/dms/"
echo "  2. 上传 dist：scp -r dms-app-frontend/dist root@<server>:/opt/dms/"
echo "  3. 运行：02-deploy-dms.sh"

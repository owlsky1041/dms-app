#!/bin/bash
#
# DMS 一键安装脚本（Debian 13.5）
# ============================================
# 用途：在裸 Debian 13.5 服务器上一键装好所有中间件 + DMS 应用
# 适用范围：生产环境、本地开发服务器、CI 测试机
#
# 使用方法：
#   1. 用 SSH/SCP 把本脚本上传到服务器：/tmp/dms-install-debian13.sh
#      或在服务器上执行：curl -fsSL https://your-server/dms-install-debian13.sh | bash
#   2. sudo bash /tmp/dms-install-debian13.sh
#
# 完成时间：约 5-10 分钟（取决于网络速度）
#

set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${GREEN}[$(date +'%H:%M:%S')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERR]${NC} $*"; }

# 要求 root
if [ "$(id -u)" -ne 0 ]; then
    err "请用 root 用户执行：sudo bash $0"
    exit 1
fi

# ============================================================
# 0. 镜像源（Debian 13 = trixie，国内用清华源加速）
# ============================================================
log "0. 配置 apt 源..."
if [ ! -f /etc/apt/sources.list.bak ]; then
    cp /etc/apt/sources.list /etc/apt/sources.list.bak
    cat > /etc/apt/sources.list <<'EOF'
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ trixie main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ trixie-updates main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ trixie-backports main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian-security trixie-security main contrib non-free non-free-firmware
EOF
fi
apt-get update -y

# ============================================================
# 1. 基础工具
# ============================================================
log "1. 安装基础工具..."
apt-get install -y --no-install-recommends \
    curl wget vim nano git \
    apt-transport-https ca-certificates gnupg dirmngr \
    sudo ufw \
    unzip zip tar \
    net-tools dnsutils \
    openjdk-21-jdk-headless \
    build-essential

# ============================================================
# 2. PostgreSQL 16（Debian 13 默认源）
# ============================================================
log "2. 安装 PostgreSQL 16..."
apt-get install -y postgresql-16 postgresql-client-16

systemctl enable postgresql
systemctl start postgresql

# 等待 PG 启动
for i in 1 2 3 4 5; do
    if sudo -u postgres psql -c 'SELECT 1' >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

# 创建 dms 数据库和用户
sudo -u postgres psql <<'EOF'
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'dms') THEN
        CREATE ROLE dms LOGIN PASSWORD 'dms_password';
    END IF;
END
$$;

SELECT 'CREATE DATABASE dms OWNER dms ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'dms')\gexec

GRANT ALL PRIVILEGES ON DATABASE dms TO dms;
\c dms
GRANT ALL ON SCHEMA public TO dms;
ALTER SCHEMA public OWNER TO dms;
EOF

# 配置 pg_hba.conf（local 用 md5 密码验证）
PG_HBA=/etc/postgresql/16/main/pg_hba.conf
if ! grep -q "local.*all.*all.*md5" $PG_HBA; then
    sed -i 's/^local\s\+all\s\+all\s\+peer/local all all md5/' $PG_HBA
fi
systemctl reload postgresql

# ============================================================
# 3. Redis 7
# ============================================================
log "3. 安装 Redis..."
apt-get install -y redis-server
systemctl enable redis-server
systemctl start redis-server

# 设置 Redis 密码（与 RuoYi 默认配置一致：ruoyi123）
if ! grep -q "^requirepass" /etc/redis/redis.conf; then
    echo "requirepass ruoyi123" >> /etc/redis/redis.conf
else
    sed -i 's/^#\?requirepass.*/requirepass ruoyi123/' /etc/redis/redis.conf
fi
# 开启 keyspace 事件（用于缓存过期通知）
if ! grep -q "^notify-keyspace-events" /etc/redis/redis.conf; then
    echo "notify-keyspace-events Ex" >> /etc/redis/redis.conf
fi
systemctl restart redis-server

# ============================================================
# 4. Nginx
# ============================================================
log "4. 安装 Nginx..."
apt-get install -y nginx
systemctl enable nginx

# ============================================================
# 5. LibreOffice（Office → PDF 转换）
# ============================================================
log "5. 安装 LibreOffice..."
apt-get install -y --no-install-recommends \
    libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress \
    libreoffice-script-provider-python \
    fonts-noto-cjk fonts-noto-cjk-extra \
    poppler-utils

# ============================================================
# 6. MinIO（对象存储）
# ============================================================
log "6. 安装 MinIO Server..."
mkdir -p /opt/minio/data

if ! id minio-user >/dev/null 2>&1; then
    useradd -r -s /sbin/nologin -d /opt/minio minio-user
fi
chown -R minio-user:minio-user /opt/minio

# 下载 MinIO 二进制
if [ ! -f /usr/local/bin/minio ]; then
    wget -q https://dl.min.io/server/minio/release/linux-amd64/minio -O /usr/local/bin/minio
    chmod +x /usr/local/bin/minio
fi

# MinIO 环境变量
cat > /etc/default/minio <<'EOF'
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin123
MINIO_VOLUMES=/opt/minio/data
MINIO_OPTS="--console-address :9001"
EOF

# systemd unit
cat > /etc/systemd/system/minio.service <<'EOF'
[Unit]
Description=MinIO Object Storage
Documentation=https://min.io
After=network.target

[Service]
User=minio-user
Group=minio-user
EnvironmentFile=-/etc/default/minio
ExecStart=/usr/local/bin/minio server $MINIO_VOLUMES $MINIO_OPTS
Restart=always
RestartSec=5
LimitNOFILE=65536
TasksMax=infinity

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable minio
systemctl start minio

# ============================================================
# 7. DMS 运行时目录
# ============================================================
log "7. 创建 DMS 运行时目录..."
mkdir -p /opt/dms/{logs,tus,uploads,nginx-conf,sql}
chown -R root:root /opt/dms
chmod -R 775 /opt/dms

# ============================================================
# 8. 防火墙
# ============================================================
log "8. 配置防火墙..."
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp      # HTTP
ufw allow 443/tcp     # HTTPS
ufw allow 9000/tcp    # MinIO API（内网）
ufw allow 9001/tcp    # MinIO Console（内网）
ufw --force enable

# ============================================================
# 9. MinIO 桶初始化
# ============================================================
log "9. 初始化 MinIO 桶..."

# 下载 mc 客户端
if [ ! -f /usr/local/bin/mc ]; then
    wget -q https://dl.min.io/client/mc/release/linux-amd64/mc -O /usr/local/bin/mc
    chmod +x /usr/local/bin/mc
fi

# 等待 MinIO 启动
for i in 1 2 3 4 5 6 7 8 9 10; do
    if curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

MC=/usr/local/bin/mc
$MC alias set local http://127.0.0.1:9000 minioadmin minioadmin123 2>&1 | head -3
$MC mb -p local/dms-files 2>&1 | head -3 || warn "桶 dms-files 可能已存在"
$MC anonymous set download local/dms-files 2>&1 | head -3 || true

# ============================================================
# 10. 总结
# ============================================================
cat <<'EOF'

╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║   ✅ DMS 环境初始化完成（Debian 13.5）                        ║
║                                                               ║
╠═══════════════════════════════════════════════════════════════╣
║                                                               ║
║   已安装：                                                    ║
║     ✓ JDK 21 (OpenJDK)                                       ║
║     ✓ PostgreSQL 16 (dms/dms_password)                       ║
║     ✓ Redis 7                                                 ║
║     ✓ Nginx                                                   ║
║     ✓ LibreOffice 7.x + 中文字体                             ║
║     ✓ MinIO Server (minioadmin/minioadmin123)                ║
║                                                               ║
║   服务状态：                                                  ║
║     systemctl status postgresql                              ║
║     systemctl status redis-server                             ║
║     systemctl status minio                                    ║
║     systemctl status nginx                                    ║
║                                                               ║
║   MinIO 桶：                                                  ║
║     local/dms-files  (公开下载)                              ║
║                                                               ║
║   下一步：                                                    ║
║     1. 上传 DMS jar：                                        ║
║        scp dms-app.jar root@<server>:/opt/dms/                ║
║     2. 上传前端 dist：                                       ║
║        scp -r dms-app-frontend/dist root@<server>:/opt/dms/   ║
║     3. 运行部署脚本：                                        ║
║        scp scripts/deploy/02-deploy-dms.sh root@<server>:/tmp/║
║        ssh root@<server> 'bash /tmp/02-deploy-dms.sh'         ║
║                                                               ║
║   防火墙端口：                                                ║
║     22, 80, 443, 9000, 9001                                  ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
EOF

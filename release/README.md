# DMS 文档管理系统 —— 发布包 v1.0.0

内网文档管理系统。**离线可部署**：发布包自带全部系统依赖，目标服务器不需要外网。

* 目标系统：**Debian 13 (trixie) / amd64**（本包在 Debian 13.5 上开发、13.5/13.7 上验证）
* 交付形态：`dms-release-1.0.0-core.tar.gz`（核心包，约 0.9GB）+ `dms-release-1.0.0-onlyoffice.tar`（可选，约 3.2GB）
* 预计部署时间：20~40 分钟

---

## 一、快速开始（3 步）

```bash
# 1) 校验并解压（务必校验：1GB 的包在传输里坏一小段，解压时才发现就晚了）
cd /root
sha256sum -c dms-release-1.0.0-SHA256SUMS
tar xzf dms-release-1.0.0-core.tar.gz && cd dms-release-1.0.0

# 2) 一键安装（离线依赖就在包内，不需要外网）
./deploy/install.sh

# 3) 装完自检（约 40 项：服务/端口/数据库/对象存储真实读写/接口/前端/定时任务）
./deploy/verify-deploy.sh
```

装完浏览器打开 `http://<服务器IP>/`，账号 `admin`，口令在 `/root/.dms-credentials`
的 `ADMIN_PASSWORD=`（安装脚本会把它改成随机强口令并**当场验证**）。**首次登录请立即改口令。**

想先灌一批示例内容再验收？执行：

```bash
./deploy/import-demo-data.sh        # 建一个「演示文档库」并导入 28 个文件
```

---

## 二、包里有什么

```
dms-release-1.0.0/
├── README.md                    ← 你正在看的这份
├── VERSION                      版本与构建信息
├── SHA256SUMS                   包内每个文件的校验和（可用 sha256sum -c 自校验）
├── docs/                        ★ 文档（先读 03-部署手册）
│   ├── 01-技术文档.md            架构、关键技术、权限模型、数据模型、关键流程
│   ├── 02-依赖与版本清单.md      每个组件的确切版本、来源、体积
│   ├── 03-部署手册.md            ★ 详细部署步骤、配置说明、HTTPS、升级、排障速查
│   ├── 04-运维手册.md            备份恢复、日志、常见故障、安全加固、容量规划
│   └── 05-优化与踩坑记录.md      设计取舍与"为什么代码长这样"（含 13 类踩坑）
├── app/
│   ├── dms-app.jar              后端（Spring Boot 可执行 jar，约 260MB）
│   └── dist/                    前端静态资源（无需在服务器上装 Node.js）
├── demo-data/                   演示数据集（28 个文件，见下）
├── sql/                         16 个 SQL：框架基础结构 + 14 个版本化迁移
├── deploy/
│   ├── install.sh               ★ 主安装脚本（幂等、可 --dry-run）
│   ├── verify-deploy.sh         ★ 部署后自检
│   ├── import-demo-data.sh      导入演示数据集
│   ├── install-onlyoffice.sh    可选：Office 在线预览/编辑
│   ├── upgrade.sh               升级（自动备份 + 失败回滚）
│   ├── scripts/                 backup.sh / restore.sh（含恢复演练 --drill）
│   └── templates/               配置模板：应用配置、systemd、nginx、logrotate、
│                                journald、MinIO、OnlyOffice
└── offline/                     ★ 离线依赖
    ├── debs/                    .deb 依赖包（含完整依赖闭包，含 Docker）
    ├── Packages                 本地 apt 源索引（apt 靠它解决依赖顺序）
    ├── binaries/{minio,mc}      MinIO 服务端与客户端（版本与验证环境一致）
    └── packages.list            包名-版本清单
```

**装了什么**：OpenJDK 21 / PostgreSQL 17 / Redis 8 / nginx / MinIO /
LibreOffice（Office 转 PDF 预览）/ ffmpeg（视频预览）/ 中文字体（Noto CJK，缺了转换会出方框）。
具体版本与包数见 `offline/packages.list` 与 `docs/02-依赖与版本清单.md`。

---

## 三、可选：Office 在线预览（OnlyOffice）

不装也能用：PDF/Office 预览会回落为「服务端 LibreOffice 转 PDF + pdf.js」，
图片/视频/文本不受影响。装了才有原生观感与在线编辑。

因为镜像有 3.3GB，它单独放在另一个包里：

```bash
tar xf dms-release-1.0.0-onlyoffice.tar           # 得到 dms-release-1.0.0-onlyoffice/
cd dms-release-1.0.0 && ./deploy/install-onlyoffice.sh \
    --image-file ../dms-release-1.0.0-onlyoffice/onlyoffice/documentserver.tar
```

安装脚本会：装 Docker → 导入镜像 → 生成并**固定**缓存链接签名密钥（三处对齐，
装完自检）→ 复制中文字体 → 启动容器 → 重载 nginx。

> ⚠️ 手工用 `docker run` 重建容器时**必须带** `-e SECURE_LINK_SECRET=$(cat /opt/onlyoffice/.secure_link_secret)`，
> 否则所有 Office/PDF 都会弹「下载失败」（原因见 05-优化与踩坑记录 第 11 节）。

---

## 四、演示数据集

`demo-data/` 里是 28 个文件，覆盖 `docx pdf xlsx csv pptx txt md html jpg png mp4`
—— 正好对应三条预览路径（图片视频原生、Office/PDF 走 OnlyOffice、文本类文本预览）：

```
1.公司制度与表单/      系统上线通知（docx/pdf/txt）、表单模板
2.技术资料/
   ├── 系统文档/       技术文档、依赖清单（docx/pdf/md/html）
   ├── 部署与运维/     部署手册、运维手册、优化记录（docx/pdf）
   └── 数据台账/       仪表台账、IO 位号清单（xlsx/csv）
3.现场照片/            5 张示例照片（含横版/竖版/方图，jpg+png）
4.培训材料/            培训课件（pptx）
5.视频资料/            12 秒示例视频（mp4，ffmpeg 生成）
```

内容全部为示例（照片来自 picsum.photos 公开示例图，视频由测试图案生成），
不含任何真实生产数据。导入方式见 `demo-data/README.md`。

---

## 五、部署前需要知道的几件事

1. **只暴露 22 与 80**：8080/8081/5432/6379/9000/9001 全部只监听 `127.0.0.1`。
   MinIO 控制台需 SSH 隧道：`ssh -L 9001:127.0.0.1:9001 root@<服务器>`。
2. **内存与磁盘**：建议 ≥4GB 内存（推荐 8GB）、系统盘 ≥20GB 可用。
   应用 JVM 堆按物理内存的 1/4 自动推算（上限 4G），可用 `--jvm-heap` 指定。
3. **口令**：安装脚本随机生成数据库/Redis/JWT/MinIO/管理员口令，写在
   `/root/.dms-credentials` 与 `/root/.minio-credentials`（600）。重复安装不会重置。
4. **安装脚本幂等**：已存在的用户/目录/库/服务不会重复创建，可以放心重跑。
   想先看它要做什么：`./deploy/install.sh --dry-run`。
5. **备份**：默认每天 02:30 自动备份到 `/opt/dms/backup`（数据库 + 对象 + 配置）。
   **默认只在本机**，防不了磁盘损坏——生产环境请按 04-运维手册第 2 节配置异机备份，
   并定期跑一次恢复演练 `restore.sh --drill`。

---

## 六、遇到问题

* 先跑 `./deploy/verify-deploy.sh`，它会指出是哪一层不通；
* 应用日志：`/opt/dms/logs/{dms.log,stdout.log,stderr.log}`（**启动失败先看 stderr.log**）；
* 常见故障速查表见 `docs/03-部署手册.md` 第 10 节；
* 「除了图片和视频，所有文档都打不开」这类现象，见 `docs/04-运维手册.md` 第 4.5b 节
  （是 OnlyOffice 签名密钥不一致，与对象存储无关）。

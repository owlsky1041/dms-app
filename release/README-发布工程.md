# 发布工程（release/）

这里是从源码构建**可离线交付的部署包**所需的全部内容：构建脚本、部署脚本、配置模板、
技术文档与演示数据集。

```
release/
├── build-release.sh          从源码构建发布包（核心包 + OnlyOffice 包）
├── fetch-offline-deps.sh     在联网机器上收集离线依赖（.deb 闭包 + MinIO 二进制 + 镜像）
├── README.md                 ★ 发布包说明书（部署方先读这份）
├── docs/                     技术文档 / 依赖版本清单 / 部署手册 / 运维手册 / 优化与踩坑记录
├── deploy/
│   ├── install.sh            ★ 一键部署（离线优先、幂等、可 --dry-run）
│   ├── verify-deploy.sh      部署后自检（约 40 项）
│   ├── import-demo-data.sh   导入演示数据集
│   ├── install-onlyoffice.sh 可选：Office 在线预览/编辑
│   ├── upgrade.sh            升级（自动备份 + 失败回滚）
│   ├── scripts/              backup.sh / restore.sh（含恢复演练）
│   └── templates/            应用配置、systemd、nginx、logrotate、journald、MinIO、OnlyOffice
└── demo-data/                演示数据集（28 个文件，覆盖各预览路径）
```

## 用法

```bash
# 1) 收集离线依赖（在一台联网的 Debian 13 / amd64 机器上执行）
./fetch-offline-deps.sh --with-onlyoffice

# 2) 构建发布包（默认读取同级目录的 dms-app / dms-app-frontend 源码树）
./build-release.sh --version 1.0.0 --with-onlyoffice-pkg
#   产物在 release/dist/：核心包 + OnlyOffice 包 + SHA256SUMS

# 3) 部署方拿到包后
tar xzf dms-release-1.0.0-core.tar.gz && cd dms-release-1.0.0
./deploy/install.sh
./deploy/verify-deploy.sh
```

源码不在同级目录时，用 `--jar/--dist/--sql-dir` 直接指定已有构建产物。

## 注意

* 发布包**不进版本库**（体积大且可重建）：本仓库只含构建它的原料与文档。
* OnlyOffice 镜像（3.2GB）来自官方公开镜像 `onlyoffice/documentserver`，不是本仓库的产物。
* 文档里出现的服务器地址、公司名等一律为占位符，请按实际环境替换。

# DMS 文档管理系统

内网使用的文档管理系统：目录树 + 文档上传下载 + 在线预览 + 四档权限 + 全操作审计。
面向生产现场（仪控/设备资料管理），可按部门/角色/用户逐目录授权，**支持离线部署**。

* 后端：Spring Boot 4.1 + RuoYi-Vue-Plus 6.0.0 基座（JDK 21），业务代码在 `ruoyi-modules/ruoyi-doc`
* 前端：[dms-app-frontend](https://github.com/owlsky1041/dms-app-frontend)（Vue 3 + Vite + Element Plus）
* 存储：PostgreSQL 17 + Redis + MinIO（对象存储）
* 预览：图片/视频原生渲染，PDF/Office/CAD 等 75+ 种走 OnlyOffice（可选），未装时回落服务端转 PDF + pdf.js
* 部署：Debian 13 / amd64，发布包自带全部依赖（离线可装），见 [`release/`](release/)

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 目录与文档 | 顶层「文档区」+ 任意层子目录；单文件上限 10GB，tus 断点续传 |
| 权限模型 | **禁止访问 / 只读 / 读写 / 完全控制** 四档 + 独立的**下载**开关；可授给用户、角色、部门；支持目录继承与到期时间 |
| 授权边界 | 文档授权需「能力权限串 + 目标资源完全控制」两道闸门；角色权限分配、清除审计日志、站点配置、新建顶层文档区仍为内置超管专属 |
| 审计 | 上传/下载/打包下载/授权/撤销/删除/重命名/移动全程留痕，可查询与导出；清除仅内置超管可做且留痕 |
| 预览 | 图片（图集）、视频（服务端转 mp4 + Plyr）、音频、PDF/Office/CAD（OnlyOffice 或服务端转 PDF） |
| 打包下载 | 小目录同步流式打包，大目录转后台任务（可查进度） |
| 运维可见 | 「系统信息」页展示服务器/应用/数据库/对象存储/缓存实时状态与业务数据量 |
| 站点配置 | 站点名称、标识图、标签页图标、备案版权、自助注册、邮箱找回密码均在页面配置 |

## 快速开始（部署）

```bash
# 发布包：在服务器上构建，或使用内网分发的离线包
cd release && ./build-release.sh --version 1.0.0

# 目标服务器（Debian 13 amd64，全新安装）
sha256sum -c dms-release-1.0.0-SHA256SUMS
tar xzf dms-release-1.0.0-core.tar.gz && cd dms-release-1.0.0
./deploy/install.sh          # 离线优先、幂等；--dry-run 可先看要做什么
./deploy/verify-deploy.sh    # 约 40 项自检，全绿才算部署成功
./deploy/import-demo-data.sh # 可选：导入演示数据集
```

文档（在 `release/docs/`，随发布包一起分发）：

| 文档 | 内容 |
| --- | --- |
| `01-技术文档.md` | 架构、关键技术、权限模型、数据模型、关键流程 |
| `02-依赖与版本清单.md` | 每个组件的确切版本、来源、体积与锁定原因 |
| `03-部署手册.md` | 详细部署步骤、配置说明、HTTPS、升级、排障速查 |
| `04-运维手册.md` | 备份恢复、日志、常见故障、安全加固、容量规划 |
| `05-优化与踩坑记录.md` | 设计取舍与踩过的坑（含"文档打不开"类故障的定位方法） |

## 开发环境

```bash
# 后端（需 JDK 21 + Maven）
mvn -DskipTests -Drevision=6.0.0 -pl ruoyi-admin -am package
java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=dev

# 前端
cd ../dms-app-frontend && pnpm install && pnpm dev
```

数据库结构随包分发：`release/deploy/sql/`（`postgres_ry_vue.sql` 为框架基础结构，
`V*.sql` 为按版本追加的迁移，用 `dms_schema_migrations` 表记录已应用版本）。

## 许可与来源

* 本项目基于 [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) v6.0.0（**MIT**）二次开发，
  保留其 `LICENSE`；本项目新增部分（`ruoyi-modules/ruoyi-doc`、前端、`release/`、文档）同样以 MIT 发布，
  详见 [`NOTICE.md`](NOTICE.md)。
* 部署包会安装若干第三方程序（PostgreSQL、Redis、nginx、MinIO、LibreOffice、ffmpeg、
  OnlyOffice），它们各自遵循自己的许可；其中 **MinIO 与 OnlyOffice 为 AGPL-3.0**，
  本仓库不含其源码，仅提供官方获取方式（见 `release/docs/02-依赖与版本清单.md`）。

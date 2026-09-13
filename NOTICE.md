# 来源与许可说明

## 本项目

DMS 文档管理系统 —— 在 [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) v6.0.0
（MIT 许可，版权归其作者）之上二次开发，新增部分（`ruoyi-modules/ruoyi-doc` 模块、
`dms-app-frontend` 前端、`release/` 发布工程与全部文档）同样以 **MIT** 许可发布。

* 上游许可证原文：`LICENSE`（RuoYi-Vue-Plus，MIT）
* 上游原始说明：`README-RuoYi基座说明.md`（含版本对照与运维备注）
* 本项目历史起点即上游 `v6.0.0` 快照（见仓库最早的提交）

## 部署包中的第三方程序

发布包（由 `release/` 构建，**不随本仓库分发**）会在目标服务器安装以下程序，
它们都是独立的第三方软件，各自遵循自己的许可：

| 组件 | 许可 | 来源 |
| --- | --- | --- |
| Debian 系统包（OpenJDK、PostgreSQL、Redis、nginx、LibreOffice、ffmpeg 等） | 各自许可（GPL/LGPL/BSD/PostgreSQL 等） | Debian 官方源 |
| MinIO | **AGPL-3.0** | 官方发布页 `dl.min.io`（版本刻意锁定，原因见依赖清单文档） |
| OnlyOffice Document Server | **AGPL-3.0** | 官方镜像 `onlyoffice/documentserver` |

**AGPL 提示**：若你对外分发包含 MinIO / OnlyOffice 的部署包，需自行履行 AGPL 的源码提供义务。
本仓库不包含这两个组件的源码或二进制，只提供官方下载地址与镜像名。

## 演示数据

`release/demo-data/` 中的示例照片取自 Lorem Picsum（`picsum.photos`）的公开示例图；
示例视频由 ffmpeg 的 `testsrc2` 测试图案生成；示例文档由本项目的文档转换而来。
不含任何真实生产数据与版权素材。

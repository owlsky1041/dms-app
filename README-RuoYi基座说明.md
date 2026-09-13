# RuoYi-Vue-Plus v6.0.0（参考 / 二次开发基础）

> 上游原始文档见 `UPSTREAM-README.md`。

| 项 | 值 |
| --- | --- |
| 上游仓库 | <https://github.com/dromara/RuoYi-Vue-Plus> |
| 版本 | **`v6.0.0` GA**（2026-07-30 发布） |
| 分支 | `6.X`（主分支）/ tag `v6.0.0` |
| Commit | `7180b529776834fee912113b23f0bd7a387a8222` |
| JDK | **21.0.12 默认** ✅（无需改 pom.xml） |
| Spring Boot | **4.1.x 全系列**（最新生态） |
| MySQL | 8.4.9（我们不用，切 PostgreSQL） |
| MinIO | RELEASE.2026-04-17T00-00-00Z |
| 许可证 | MIT |

> ⚠️ 上表是**上游模板**的版本，不是本项目生产环境的实际版本。生产实际部署情况见下表——
> 之前就出现过"照 README 以为跑的是 MinIO 2026-04-17，实际是 2025-09-07"的混淆。

## 本项目生产环境实际版本

| 组件 | 实际版本 | 说明 |
| --- | --- | --- |
| MinIO | **RELEASE.2025-04-22T22-12-26Z** | 原生二进制部署（非容器），systemd 服务 `minio`，数据 `/opt/minio/data`，控制台 `:9001` |
| PostgreSQL | 17.11 | 与本机同机，仅监听 127.0.0.1 |
| Redis | 8.0.2 | 仅监听 127.0.0.1 |
| OnlyOffice | docker `onlyoffice/documentserver:latest` | 端口 8081 |
| JDK | 21 | 应用以 `-Xmx2g` 运行 |

## 生产运维（P0 加固后）

### 备份与恢复

```bash
/opt/dms/backup/backup.sh              # 手动备份一次
/opt/dms/backup/restore.sh --list      # 看有哪些备份
/opt/dms/backup/restore.sh --drill     # 恢复演练：还原到临时库/临时桶并逐项核对（不碰现网）
/opt/dms/backup/restore.sh --db STAMP  # 真还原数据库（破坏性，需输 yes）
/opt/dms/backup/restore.sh --all STAMP # 数据库 + 对象一起还原
```

* 备份内容：`pg_dump` 全库 + MinIO 对象镜像 + 配置与版本信息 + 对象清单（含 ETag）
* 定时：systemd timer `dms-backup.timer`，每天 02:30，保留 30 天，日志 `/opt/dms/backup/backup.log`
* **重要**：默认只备份到本机 `/opt/dms/backup`，能防误删/误操作，**防不了磁盘或整机故障**。
  要真正防灾需设置 `DMS_BACKUP_REMOTE=nas:/path`（脚本里已预留 rsync 出口）。
* 每次备份会自动校验（`pg_restore -l` 可读 + 对象数一致），失败会 exit 1。

### 凭据

两套凭据都在服务器 **`/root/.dms-credentials`（权限 600）**，不在代码库里：

| 用途 | 说明 |
| --- | --- |
| MinIO Access Key | 应用专用（用户 `dms-app` + 策略 `dms-files-rw`，仅 `dms-files` 桶） |
| MinIO root | 仅控制台管理用，已换掉默认口令 |
| admin / DB / Redis | 应用登录、数据库、缓存口令 |
| JWT_SECRET | Sa-Token 签名密钥，换掉后所有会话失效 |

### 语言与时区（JVM 启动参数）

```
-Duser.language=zh -Duser.country=CN -Duser.timezone=Asia/Shanghai
```

为什么显式指定：

* 后端框架级提示（如"不允许重复提交，请稍候再试"）走 i18n，语言由请求头
  `content-language` 决定；前端不带这个头时会回退到 **JVM 默认区域**。
  服务器 JVM 默认是 `en_US`，于是这类提示会显示成英文 —— 已固定为 `zh_CN`。
  （如果以后要做多语言界面，让前端带上 `content-language` 请求头即可，这里是兜底。）
* 时区显式固定，避免系统时区变动导致审计日志时间错位。

⚠️ systemd 的 `ExecStart` **不要用行续接（行尾 `\`）**：systemd 会把续行的缩进
并入参数，产生 `" -Duser.language=zh"` 这种带前导空格的参数，JVM 会把它当类名，
报 `Could not find or load main class`。必须写成一整行。

### 安全边界

* 只有 nginx 的 **80** 对外（内网）。后端 8080、OnlyOffice 8081、MinIO 9000/9001 全部只监听 `127.0.0.1`
* 防火墙 ufw 启用：入站仅放行内网段的 22 与 80。80/443 因运营商封端口未用于外部访问，
  将来若做域名/反代，在此追加端口即可
* 应用以专用账号 `dms` 运行，systemd 加固：`NoNewPrivileges`、`ProtectSystem=strict`、
  可写路径仅 `/opt/dms/{logs,export}`、`/var/dms/tus`、`/tmp`
* MinIO 控制台需经 SSH 隧道访问：`ssh -L 9001:127.0.0.1:9001 root@<服务器地址>`，再开 `http://localhost:9001`

### 权限模型：兜底层 与 可委派

系统里只有一个**真正的**超级管理员：`SystemConstants.SUPER_ADMIN_USER_ID`
（= `1761100000000000001`）。他的权限不是靠角色，而是 `SysPermissionServiceImpl`
按用户 ID 注入 `*:*:*`，所以即使角色/授权数据被误删，这个账号依然能进系统救场。
**「超级管理员」那个角色行是个空壳**（`sys_role_menu` 里 0 条），不能当权限用，
也不允许分配给其他用户。

在此之外，管理能力分成两层：

| 层 | 能力 | 判定方式 |
| --- | --- | --- |
| 兜底层（只能内置超管做） | 角色权限分配 `/system/role/permission`、菜单管理写接口、清除审计日志、站点配置、新建/调整顶层文档区、操作内置超管账号 | 用户 ID 判定（菜单管理沿用上游的 `@SaCheckRole(superadmin)`） |
| 可委派（超管在「角色管理 → 分配权限」里勾给某个角色） | 审计日志查看 `system:audit:list`、审计导出 `system:audit:export`、系统信息 `system:info:list`、文档授权 `doc:perm:grant` / `doc:perm:full` | 权限串 `@SaCheckPermission` |

两条关键设计：

* **角色权限分配必须锁死**。它是权限体系的"总闸"——谁能拿到哪些权限串全由它写库。
  一旦放开，持有者可以给自己补任意菜单，属于无限自我提权；而且它本身就能改写
  "判断依据"，所以不能再用权限串兜它，只能落到用户 ID 这个不可自我修改的事实上。
* **文档授权是双闸门**：能力闸门 `doc:perm:grant`（或 `doc:perm:full`）**且**在该目录/文件上
  有「完全控制」位（128）。只查前者会越权改别人的目录，只查后者会自我复制提权。
  这一层也正好补上了代码里早就声明的语义：完全控制 = 读写 + 下载 + **授权**。

被委派的人不需要额外开放「用户管理/角色管理/部门管理」：授权弹窗的主体名单走
`GET /api/perm/subjects`（同样只要 `doc:perm:grant`），只返回名字，不返回邮箱手机号。

### 站点标识图 / 标签页图标（可配置）

登录页标题上方与登录后页头左上角那张图，和浏览器标签页的小图标，是**两个独立文件**：

| 项目 | 存在哪 | 说明 |
| --- | --- | --- |
| 站点标识图 `logo` | `/opt/dms/site-assets/logo.<ext>` | 给人看的，建议 128×128 以上；登录页按 64px、页头按 26px 等比缩放 |
| 标签页图标 `favicon` | `/opt/dms/site-assets/favicon.<ext>` | 浏览器标签页 16×16 用，建议 64×64 |

站名、标识图都在「站点配置」里改，改完登录页与页头立即生效（站名同时用于浏览器标签标题）。

**运维要点（踩过的坑）**：`dms.site.asset-dir` 默认 `/opt/dms/site-assets`，而 systemd 用了
`ProtectSystem=strict` + 白名单 `ReadWritePaths`。这个目录原先不在白名单里、属主还是别人，
应用以 `dms` 身份跑根本写不进去，上传会报 `Read-only file system`——
**favicon 上传因此坏了很久没人发现**（谁都没在页面上传过图标）。
新增可写的站点资源目录时，两件事都要做：

```bash
mkdir -p /opt/dms/site-assets && chown -R dms:dms /opt/dms/site-assets
# 并把该目录加进 systemd 的 ReadWritePaths，然后 daemon-reload + restart
```

### 验收脚本的收尾

跑完任何验收脚本后执行一次：

```bash
python3 tools/minio_sweep_orphans.py            # 只报告
python3 tools/minio_sweep_orphans.py --delete   # 真删
```

脚本只删"库里已无引用"的对象（`files/`↔`storage_key`、`previews/`↔`preview_key`、
`thumbs/`↔`thumbnail_key` 三处分别比对）。只删库里的行不删对象，会在对象存储里攒孤儿
对象，几次之后「MinIO 对象数 vs 业务文件数」就对不上，看着像系统丢文件。
审计记录同理：验收脚本一律用"起始最大 `log_id` 水位线"清理本次产生的记录
（超管令牌产生的记录只按临时用户 ID 是清不掉的）。

### tus 上传临时文件

上传中断（关页面/断网）会留下临时文件，单文件上限 10GB，不清会吃满系统盘。
`TusCleanupJob` 每天 04:20 清理：先调 tus 官方 `cleanup()`，再按目录时间戳兜底删掉
超过 `stale-hours`(48h) 未变动的残留；占用超过 `warn-bytes`(5GB) 打 ERROR 日志作为告警触发点。

### MinIO 为什么锁在 2025-04-22

MinIO 官方在后续版本里移除了 Web 管理界面（约 11 万行代码），新版控制台连 Access Key 都
无法配置，只剩下一个对象浏览器。2025-04-22 是**最后一个带完整管理控制台**的版本，
因此刻意锁定在这个版本，不再跟随上游升级。

运维要点：

* **凭据**：应用使用最小权限 Access Key（用户 `dms-app` + 策略 `dms-files-rw`，
  只允许读写 `dms-files` 一个桶）；root 账号只用于控制台管理。
  两套凭据都记录在服务器 `/root/.minio-credentials`（权限 600）。
* **不要再按上表升级 MinIO**：升级会丢掉控制台；如需升级必须先确认新版本的控制台能力。
* 回滚：上一版二进制保留在 `/opt/dms/backup/minio.RELEASE.2025-09-07T16-13-09Z`。

## v6.0.0 相比 5.X 的关键变化

### ✅ 利好

1. **JDK 21 默认** —— 不需要手动改 `<java.version>`，直接 `mvn package`
2. **MyBatis-Plus-Join（MPJ）集成** —— doc 模块 folder/file/perm 三表 join 大幅简化
3. **新增 `ruoyi-api` 模块** —— doc 模块可独立抽出 SDK
4. **大文件分片断点续传修复** —— tus 集成风险↓
5. **Date → LocalDateTime 全局** —— 与 PostgreSQL 现代字段类型契合
6. **多租户移除** —— 我们不用，白赚
7. **snail-ai / Spring AI 2.0 / LiteFlow / MCP / MQTT / ES** —— 大量新模块，按需取
8. **idempotent / ratelimiter 合并入 redis 模块** —— 依赖更少
9. **`mvnw` / `mvnw.cmd`** —— 自带 Maven Wrapper，构建环境一致（v6 新增）
10. **Springdoc 3.0.2** —— OpenAPI 文档新版

### ⚠️ 注意事项

1. **Spring Boot 4.X** —— 生态最前沿，部分插件可能需要验证（tus-java-server 1.0.0-3.3 在 SB 3.x 验证过，SB 4.x 兼容性需测试）
2. **ruoyi-common 模块重构** —— 旧文档提到的 `common-sse/common-websocket` 已并入 `common-push`
3. **ruoyi-common-ai / common-mcp / common-mqtt / common-elasticsearch** —— 这些本期都不用，删除它们
4. **warmflow + LiteFlow 双规则引擎** —— 文档说不要工作流，整个 `ruoyi-workflow` 模块要删

## DMS 需要新增 / 调整的内容

- [ ] 新增 `ruoyi-modules/ruoyi-doc`：文档管理核心模块
- [ ] 切换默认数据库：MySQL 8.4 → PostgreSQL 16（修改 `ruoyi-common-mybatis` 的 driver 依赖 + 各模块 `application*.yml`）
- [ ] 重写 Flyway 迁移脚本（保留 RuoYi 的 sys_* 表，新增 doc_* 表）
- [ ] 删除无用模块：
  - `ruoyi-modules/ruoyi-workflow`（不要工作流）
  - `ruoyi-modules/ruoyi-gen`（代码生成器）
  - `ruoyi-modules/ruoyi-job`（XXL-JOB 用 @Scheduled 替代）
  - `ruoyi-modules/ruoyi-demo`（演示）
  - `ruoyi-modules/ruoyi-ai`（AI 暂不接）
  - `ruoyi-common-ai / common-mcp / common-mqtt / common-elasticsearch`（本期用不到）
  - `ruoyi-common-liteflow`（不要规则引擎）
  - `ruoyi-extend/ruoyi-monitor-admin`（不要监控）
  - `ruoyi-extend/ruoyi-snailjob-server`（用 @Scheduled）
- [ ] doc 模块添加依赖：`ruoyi-common-mybatis`（JPA 可选）、`ruoyi-common-oss`、`ruoyi-api`、`ruoyi-system`

## 上游同步策略

```bash
git remote add upstream https://github.com/dromara/RuoYi-Vue-Plus.git
git fetch upstream 6.X
git rebase upstream/6.X  # 冲突时手工合并
```

## 上游关键文档

- `UPSTREAM-README.md`：上游原始说明
- `mvnw` / `mvnw.cmd`：Maven Wrapper（推荐使用，不依赖系统 mvn）
- `script/`：sql、docker、nginx 脚本
- `ruoyi-api/`：**新模块**，通用 Service 接口与实体类的统一存放处

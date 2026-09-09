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

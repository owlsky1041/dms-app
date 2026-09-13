# DMS 演示数据集

给**新部署**的系统灌一套"看起来像真的在用"的示例内容，用于：
* 部署后立刻能点开东西验证（而不是对着空白页面）；
* 覆盖三条预览路径与各类权限/下载场景；
* 演示与培训时不用翻真实资料。

## 内容

```
files/
├── 1.公司制度与表单/      通知、表单模板（pdf / docx / txt）
├── 2.技术资料/
│   ├── 系统文档/          技术文档、依赖清单（docx / pdf / md / html）
│   ├── 部署与运维/        部署手册、运维手册、优化记录（docx / pdf）
│   └── 数据台账/          仪表台账、IO 位号清单（xlsx / csv）
├── 3.现场照片/            风景示例照片（jpg ×4、png ×1，含横版/竖版/方图）
├── 4.培训材料/            培训课件（pptx）
└── 5.视频资料/            示例视频（mp4，12 秒，ffmpeg 生成）
```

共 **28 个文件**，格式覆盖 `docx pdf xlsx csv pptx txt md html jpg png mp4`。

## 导入

```bash
cd dms-release-1.0.0
./deploy/import-demo-data.sh                 # 新建「演示文档库」文档区并灌入
./deploy/import-demo-data.sh --zone-name 培训资料
./deploy/import-demo-data.sh --parent 192     # 挂到已有目录下
./deploy/import-demo-data.sh --dry-run        # 先看要做什么
```

脚本用 `/root/.dms-credentials` 里的管理员账号登录，走的是与前端**同一条**链路：
创建目录 → tus 断点续传上传 → 触发预览/缩略图生成。因此导入后看到的预览、审计记录、
权限表现与手动上传完全一致。

## 说明

* 内容全部为示例，**不含任何真实生产数据**；
* 照片来自 Lorem Picsum（`picsum.photos`）的公开示例图；演示视频由 ffmpeg 的
  `testsrc2` 测试图案生成，不含版权素材；
* 文档由本项目的《部署手册》《技术文档》《运维手册》《依赖与版本清单》《优化与踩坑记录》
  经 LibreOffice 转换而来，属于本仓库内容。

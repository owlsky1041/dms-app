DMS 演示数据集说明
====================

本数据集用于部署完成后快速把系统"用起来"，也用于验收各类文件的预览/下载/权限表现。
内容全部是可公开的示例内容，不含任何真实生产数据。

目录结构
--------
1.管理制度/            制度、表单模板（docx / pdf / txt）
2.技术资料/
   ├── 系统文档/       技术文档、依赖清单（docx / pdf / md / html）
   ├── 部署与运维/     部署手册、运维手册、培训课件（docx / pdf / pptx）
   └── 数据台账/       仪表台账、IO 位号清单（xlsx / csv）
3.现场照片/            风景示例照片（jpg / png）
4.视频资料/            示例视频（mp4）

格式覆盖
--------
docx、pdf、xlsx、csv、pptx、txt、md、html、jpg、png、mp4
覆盖"图片/视频原生渲染、Office/PDF 走 OnlyOffice、文本类走文本预览"三条预览路径。

导入方式
--------
./deploy/import-demo-data.sh                       # 建一个演示文档区并灌入
./deploy/import-demo-data.sh --parent <目录ID>      # 挂到指定目录下
./deploy/import-demo-data.sh --zone-name <名称>     # 自定义文档区名称

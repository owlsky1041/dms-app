-- ============================================================
-- V1.9.0  默认数据：doc 模块菜单 + 系统参数
-- ============================================================
-- 注意：本脚本必须匹配 RuoYi-Vue-Plus 6.0 PostgreSQL 版表结构！
--   sys_menu.is_frame  = 'N'/'Y'
--   sys_menu.is_cache  = 'Y'/'N'
--   sys_menu.visible   = '0'/'1'
--   sys_menu.status    = '0'/'1'
-- ============================================================

-- 1. doc 模块菜单（目录）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (2000, '文档管理', 0, 5, 'doc', null, null, 'N', 'Y', 'M', '0', '0', '', 'folder', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 'DMS 文档管理目录')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
-- 说明：不设「我的文档」个人空间，全公司共用一套文档库（顶层为公司各文档区）
VALUES (2010, '全部文档', 2000, 1, 'all', 'doc/all/index', null, 'N', 'Y', 'C', '0', '0', 'doc:all:list', 'folder', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '公司全部文档（顶层入口）'),
       (2020, '部门资料', 2000, 2, 'library', 'doc/library/index', null, 'N', 'Y', 'C', '0', '0', 'doc:library:list', 'folder', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '部门资料（仅名称，非按部门拆分文档库）'),
       (2040, '回收站',   2000, 4, 'recycle', 'doc/recycle/index', null, 'N', 'Y', 'C', '0', '0', 'doc:recycle:list', 'delete', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '回收站')
ON CONFLICT (menu_id) DO NOTHING;

-- 2. doc 模块按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (2011, '文件查询',   2010, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:file:query',    '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2012, '文件上传',   2010, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:file:upload',   '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2013, '文件下载',   2010, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:file:download', '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2014, '文件预览',   2010, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:file:preview',  '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2015, '文件编辑',   2010, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:file:edit',     '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2016, '文件删除',   2010, 6, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:file:delete',   '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2017, '文件夹创建', 2010, 7, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:folder:create', '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2018, '权限分配',   2010, 8, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:perm:grant',    '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (2019, '完全控制',   2010, 9, '', null, null, 'N', 'Y', 'F', '0', '0', 'doc:perm:full',     '#', '', '', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '')
ON CONFLICT (menu_id) DO NOTHING;

-- 3. doc 模块系统参数
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (100, '上传分块大小', 'sys.upload.chunkSize', '5242880', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '单位字节，默认 5MB'),
       (101, '单文件最大', 'sys.upload.maxFileSize', '1073741824', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '单位字节，默认 1GB'),
       (102, '单文件夹最大', 'sys.upload.maxFolderSize', '10737418240', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '单位字节，默认 10GB'),
       (103, '单次最多文件数', 'sys.upload.maxFileCount', '1000', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (104, '回收站保留天数', 'sys.recycle.retentionDays', '30', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ''),
       (105, 'PDF 预览最大', 'sys.preview.maxPdfSize', '52428800', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '单位字节，默认 50MB'),
       (106, '权限缓存 TTL', 'sys.perm.cache.ttl', '300', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, '单位秒，默认 5 分钟'),
       (107, 'MinIO 桶名', 'dms.minio.bucket', 'dms-files', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 'DMS 文件桶'),
       (108, 'tus 上传临时目录', 'dms.tus.tempDir', '/var/dms/tus', 'Y', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 'tus 分块临时目录')
ON CONFLICT (config_id) DO NOTHING;

-- 4. 字典类型：文件类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (100, '文件类型', 'dms_file_type', 103, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 'DMS 文件扩展名分类')
ON CONFLICT (dict_type) DO NOTHING;

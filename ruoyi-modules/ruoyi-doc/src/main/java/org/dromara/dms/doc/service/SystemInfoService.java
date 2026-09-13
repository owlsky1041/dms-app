package org.dromara.dms.doc.service;

import java.util.Map;

/**
 * 系统信息（服务器 / 应用 / 磁盘 / 数据库 / MinIO / Redis）
 *
 * <p>只读、只观测，不做任何写操作——这个页面会在前端定时刷新，
 * 任何一次采集都不该改变系统状态。
 *
 * @author DMS
 */
public interface SystemInfoService {

    /**
     * 采集一份系统快照
     *
     * @return 分组的只读快照：server / jvm / disks / database / minio / redis / business
     */
    Map<String, Object> snapshot();
}

package org.dromara.dms.doc.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.service.SystemInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统信息（服务器 / 应用 / 磁盘 / 数据库 / MinIO / Redis）
 *
 * <p>只读接口，由权限串 {@code system:info:list} 控制：这里会暴露主机名、目录路径、
 * 内存与磁盘余量等基础设施信息，不该给普通用户看，但可以由内置超管授予管理员角色——
 * 运维值班的人需要能看到这些。
 *
 * @author DMS
 */
@RestController
@RequestMapping("/api/doc/system")
@RequiredArgsConstructor
public class SystemInfoController {

    private final SystemInfoService systemInfoService;

    /** 采集一份快照（前端定时刷新） */
    @SaCheckPermission("system:info:list")
    @GetMapping("/info")
    public R<Map<String, Object>> info() {
        return R.ok(systemInfoService.snapshot());
    }
}

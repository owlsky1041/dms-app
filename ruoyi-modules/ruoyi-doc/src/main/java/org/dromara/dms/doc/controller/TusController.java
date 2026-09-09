package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * tus 协议端点（1.0.0-3.3 API）
 *
 * <p>所有方法（POST/PATCH/HEAD/DELETE/OPTIONS/GET）统一转发给 tus 服务。
 *
 * <p>ownerKey 用当前登录用户 userId 隔离多用户上传会话，
 * 与 {@code POST /api/upload/{uploadId}/complete} 回调里的 ownerKey 保持一致。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/upload/tus")
@RequiredArgsConstructor
public class TusController {

    private final TusFileUploadService tusService;

    @RequestMapping(value = {"", "/**"}, method = {
            RequestMethod.POST,
            RequestMethod.PATCH,
            RequestMethod.HEAD,
            RequestMethod.DELETE,
            RequestMethod.OPTIONS,
            RequestMethod.GET
    })
    public void handleTus(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        String ownerKey = String.valueOf(LoginHelper.getUserId());
        // 1.0.0-3.3：process() 返回 void
        tusService.process(request, response, ownerKey);
    }
}

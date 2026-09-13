package org.dromara.dms.doc.dto;

import lombok.Data;

/**
 * 用户自助注册请求
 *
 * @author DMS
 */
@Data
public class SiteRegisterRequest {

    /** 登录账号 */
    private String username;

    /** 登录密码 */
    private String password;

    /** 确认密码 */
    private String confirmPassword;

    /** 姓名/昵称，留空则与账号相同 */
    private String nickName;

    /** 邮箱（找回密码依赖此邮箱，建议填写） */
    private String email;

    /** 手机号 */
    private String phoneNumber;
}

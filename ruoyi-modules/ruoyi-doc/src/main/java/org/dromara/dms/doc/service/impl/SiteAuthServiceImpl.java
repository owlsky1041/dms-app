package org.dromara.dms.doc.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.core.lang.Validator;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.collection.CollUtil;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.enums.UserType;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.dms.doc.domain.SysSiteConfig;
import org.dromara.dms.doc.dto.PasswordResetRequest;
import org.dromara.dms.doc.dto.SiteRegisterRequest;
import org.dromara.dms.doc.service.SiteAuthService;
import org.dromara.dms.doc.service.SiteConfigService;
import org.dromara.dms.doc.service.SiteMailService;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 站点账号自助服务实现
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteAuthServiceImpl implements SiteAuthService {

    /** 验证码缓存键前缀 */
    private static final String CODE_KEY = "dms:pwd:reset:code:";
    /** 发送频率限制键前缀 */
    private static final String LIMIT_KEY = "dms:pwd:reset:limit:";
    /** 验证失败次数键前缀 */
    private static final String FAIL_KEY = "dms:pwd:reset:fail:";

    /** 验证码有效期（分钟） */
    private static final int CODE_TTL_MINUTES = 5;
    /** 同一账号两次发送的最小间隔（秒） */
    private static final int SEND_INTERVAL_SECONDS = 60;
    /** 同一验证码允许的最大校验失败次数，超过后验证码作废 */
    private static final int MAX_VERIFY_FAIL = 5;

    /** 登录账号：2-30 位，字母/数字/下划线/中文 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,30}$");
    /** 手机号（中国大陆） */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final SiteConfigService siteConfigService;
    private final SiteMailService siteMailService;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    // ==================================================================
    // 注册
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(SiteRegisterRequest req) {
        SysSiteConfig config = siteConfigService.get();
        if (!Boolean.TRUE.equals(config.getRegisterEnabled())) {
            throw new ServiceException("系统当前未开放自助注册，请联系管理员开通账号");
        }

        String username = trim(req.getUsername());
        String password = req.getPassword();
        String confirm = req.getConfirmPassword();
        String email = trim(req.getEmail());
        String phone = trim(req.getPhoneNumber());

        if (username.isEmpty()) {
            throw new ServiceException("请输入登录账号");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ServiceException("账号只能包含字母、数字、下划线或中文，长度 2-30 位");
        }
        if (password == null || password.length() < 6 || password.length() > 30) {
            throw new ServiceException("密码长度需为 6-30 位");
        }
        if (!password.equals(confirm)) {
            throw new ServiceException("两次输入的密码不一致");
        }
        if (!email.isEmpty() && !Validator.isEmail(email)) {
            throw new ServiceException("邮箱格式不正确");
        }
        if (!phone.isEmpty() && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new ServiceException("手机号格式不正确");
        }

        // 账号唯一（user_name 上有唯一索引，这里先查一次给出友好提示）
        if (anonymous(() -> sysUserMapper.lambda().eq(SysUser::getUserName, username).exists())) {
            throw new ServiceException("账号「" + username + "」已被占用，请更换");
        }
        // 邮箱唯一：找回密码需要靠邮箱定位唯一账号
        if (!email.isEmpty() && anonymous(() -> sysUserMapper.lambda().eq(SysUser::getEmail, email).exists())) {
            throw new ServiceException("该邮箱已被其它账号使用");
        }
        if (!phone.isEmpty() && anonymous(() -> sysUserMapper.lambda().eq(SysUser::getPhoneNumber, phone).exists())) {
            throw new ServiceException("该手机号已被其它账号使用");
        }

        SysUser user = new SysUser();
        user.setUserName(username);
        user.setNickName(StrUtil.blankToDefault(trim(req.getNickName()), username));
        user.setUserType("sys_user");
        user.setPassword(BCrypt.hashpw(password));
        user.setEmail(email);
        user.setPhoneNumber(phone);
        user.setGender("0");
        user.setStatus(SystemConstants.NORMAL);
        user.setDelFlag("0");
        user.setRemark("用户自助注册");
        // 匿名请求没有登录态，自动填充会写成 -1；显式置 0 表示「系统创建」
        user.setCreateBy(0L);
        user.setUpdateBy(0L);
        if (anonymous(() -> sysUserMapper.insert(user)) <= 0) {
            throw new ServiceException("注册失败，请稍后重试");
        }

        // 默认角色：配置了才授权，未配置则等待管理员分配
        assignDefaultRoles(user.getUserId(), config.getRegisterRoleIds());

        log.info("用户自助注册成功: userName={}, userId={}, email={}", username, user.getUserId(), email);
    }

    /** 按站点配置给新用户授予默认角色 */
    private void assignDefaultRoles(Long userId, String roleIds) {
        if (userId == null || roleIds == null || roleIds.isBlank()) {
            return;
        }
        for (String part : roleIds.split(",")) {
            String id = part.trim();
            if (id.isEmpty() || !id.chars().allMatch(Character::isDigit)) {
                continue;
            }
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(Long.valueOf(id));
            anonymous(() -> sysUserRoleMapper.insert(userRole));
        }
    }

    // ==================================================================
    // 找回密码 —— 申请验证码
    // ==================================================================

    @Override
    public ResetCodeResult sendResetCode(String account) {
        SysSiteConfig config = siteConfigService.get();
        if (!Boolean.TRUE.equals(config.getResetEnabled())) {
            throw new ServiceException("系统未开放邮箱找回密码，请联系管理员重置");
        }
        if (!siteMailService.isReady(config)) {
            throw new ServiceException("邮件服务尚未配置完成，请联系管理员");
        }
        String raw = account == null ? "" : account.trim();
        if (raw.isEmpty()) {
            throw new ServiceException("请输入登录账号或邮箱");
        }

        SysUser user = findUser(raw);
        if (user == null) {
            throw new ServiceException("账号不存在，请检查后重试");
        }
        if (!SystemConstants.NORMAL.equals(user.getStatus())) {
            throw new ServiceException("账号已停用，请联系管理员");
        }
        String email = trim(user.getEmail());
        if (email.isEmpty()) {
            throw new ServiceException("该账号未绑定邮箱，无法自助找回，请联系管理员重置密码");
        }
        // 键以 userId 为准：用户可能这次用账号、下次用邮箱，必须命中同一份验证码
        String key = userKey(user);

        // 频率限制：60 秒内只允许发一次
        if (!RedisUtils.setObjectIfAbsent(LIMIT_KEY + key, 1, Duration.ofSeconds(SEND_INTERVAL_SECONDS))) {
            throw new ServiceException("验证码发送过于频繁，请 " + remainSeconds(LIMIT_KEY + key) + " 秒后再试");
        }

        String code = RandomUtil.randomNumbers(6);
        RedisUtils.setCacheObject(CODE_KEY + key, code, Duration.ofMinutes(CODE_TTL_MINUTES));
        RedisUtils.deleteObject(FAIL_KEY + key);
        try {
            siteMailService.sendPasswordResetCode(email, code, CODE_TTL_MINUTES);
        } catch (RuntimeException e) {
            // 发送失败就释放限流与验证码，允许用户立刻重试
            RedisUtils.deleteObject(LIMIT_KEY + key);
            RedisUtils.deleteObject(CODE_KEY + key);
            throw e;
        }
        return new ResetCodeResult(CODE_TTL_MINUTES, maskEmail(email));
    }

    // ==================================================================
    // 找回密码 —— 校验验证码并重置
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(PasswordResetRequest req) {
        SysSiteConfig config = siteConfigService.get();
        if (!Boolean.TRUE.equals(config.getResetEnabled())) {
            throw new ServiceException("系统未开放邮箱找回密码，请联系管理员重置");
        }
        String raw = req.getAccount() == null ? "" : req.getAccount().trim();
        if (raw.isEmpty()) {
            throw new ServiceException("请输入登录账号或邮箱");
        }
        String code = trim(req.getCode());
        if (code.isEmpty()) {
            throw new ServiceException("请输入邮件验证码");
        }
        String password = req.getPassword();
        if (password == null || password.length() < 6 || password.length() > 30) {
            throw new ServiceException("密码长度需为 6-30 位");
        }
        if (!password.equals(req.getConfirmPassword())) {
            throw new ServiceException("两次输入的密码不一致");
        }

        SysUser user = findUser(raw);
        if (user == null) {
            throw new ServiceException("账号不存在，请检查后重试");
        }
        // 与 sendResetCode 保持一致：验证码以 userId 为键，账号/邮箱两种输入都能命中
        String key = userKey(user);

        String cached = RedisUtils.getCacheObject(CODE_KEY + key);
        if (cached == null) {
            throw new ServiceException("验证码已过期，请重新获取");
        }
        if (!cached.equals(code)) {
            int fails = increaseFail(key);
            if (fails >= MAX_VERIFY_FAIL) {
                RedisUtils.deleteObject(CODE_KEY + key);
                RedisUtils.deleteObject(FAIL_KEY + key);
                throw new ServiceException("验证码错误次数过多，验证码已作废，请重新获取");
            }
            throw new ServiceException("验证码不正确，还可尝试 " + (MAX_VERIFY_FAIL - fails) + " 次");
        }

        if (BCrypt.checkpw(password, user.getPassword())) {
            throw new ServiceException("新密码不能与原密码相同");
        }

        SysUser update = new SysUser();
        update.setUserId(user.getUserId());
        update.setPassword(BCrypt.hashpw(password));
        update.setUpdateBy(0L);
        if (anonymous(() -> sysUserMapper.updateById(update)) <= 0) {
            throw new ServiceException("密码重置失败，请稍后重试");
        }

        // 用掉即失效，避免同一验证码重复使用；限流键保留，让 60 秒间隔继续生效
        RedisUtils.deleteObject(CODE_KEY + key);
        RedisUtils.deleteObject(FAIL_KEY + key);

        // 密码已变更，踢掉该账号所有已登录会话，避免旧 token 继续可用
        kickoutAllSessions(user.getUserId(), user.getUserType(), user.getUserName());

        log.info("用户通过邮箱验证码重置密码: userName={}, userId={}", user.getUserName(), user.getUserId());
    }

    /**
     * 踢掉指定账号的全部登录会话
     *
     * <p>密码已变更，旧 token 不应继续可用。这里把两个来源取并集：
     * <ol>
     *   <li>Sa-Token 自己的账号会话：{@code getTokenValueListByLoginId}</li>
     *   <li>RuoYi 的在线 token 注册表 {@code online_tokens:*} 兜底</li>
     * </ol>
     * 然后逐个调用 {@code kickoutByTokenValue} —— 它不依赖当前请求的登录态，
     * 在匿名的找回密码请求里同样有效（与「在线用户」页强制下线用的是同一个 API）。
     *
     * <p><b>注意</b>：Sa-Token 中的 loginId 不是裸 userId，而是
     * {@code userType + ":" + userId}（见 {@code LoginUser.getLoginId()}），
     * 传裸 userId 会查不到任何会话。
     */
    private void kickoutAllSessions(Long userId, String userType, String userName) {
        try {
            String loginId = UserType.getUserType(userType).getUserType() + ":" + userId;
            Set<String> tokens = new LinkedHashSet<>();
            List<String> byLoginId = StpUtil.stpLogic.getTokenValueListByLoginId(loginId);
            if (CollUtil.isNotEmpty(byLoginId)) {
                tokens.addAll(byLoginId);
            }
            for (String key : RedisUtils.keys(CacheNames.ONLINE_TOKEN_KEY + "*")) {
                String token = StringUtils.substringAfterLast(key, StringUtils.COLON);
                if (StringUtils.isBlank(token)) {
                    continue;
                }
                if (loginId.equals(String.valueOf(StpUtil.stpLogic.getLoginIdByToken(token)))) {
                    tokens.add(token);
                }
            }
            tokens.removeIf(StringUtils::isBlank);

            for (String token : tokens) {
                StpUtil.kickoutByTokenValue(token);
            }
            log.info("[密码重置] 已清理 userName={} 的登录会话 {} 个", userName, tokens.size());
        } catch (Exception e) {
            // 清理失败不影响「密码已重置」这一事实，但要留下告警便于排查
            log.warn("[密码重置] 清理 userName={} 的登录会话失败", userName, e);
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 累加并返回当前验证失败次数（与验证码同生命周期）
     *
     * <p>计数以<b>字符串</b>存取：JSON 编解码器会把数字还原成 Integer，
     * 若按 Long 接收会抛 {@code ClassCastException: Integer cannot be cast to Long}
     * （日志里表现为「发生未知异常」）。
     */
    private int increaseFail(String key) {
        Object raw = RedisUtils.getCacheObject(FAIL_KEY + key);
        int next = 1;
        if (raw != null) {
            try {
                next = Integer.parseInt(String.valueOf(raw).trim()) + 1;
            } catch (NumberFormatException e) {
                next = 1;
            }
        }
        RedisUtils.setCacheObject(FAIL_KEY + key, String.valueOf(next), Duration.ofMinutes(CODE_TTL_MINUTES));
        return next;
    }

    /**
     * 按账号或邮箱定位用户
     *
     * <p>用 {@code one(false)}：历史库中可能存在重复邮箱，此时取第一条而不是抛异常。
     */
    private SysUser findUser(String key) {
        SysUser user = anonymous(() -> sysUserMapper.lambda().eq(SysUser::getUserName, key).one(false));
        if (user == null && key.contains("@")) {
            List<SysUser> list = anonymous(() -> sysUserMapper.lambda()
                    .eq(SysUser::getEmail, key)
                    .list());
            user = list.isEmpty() ? null : list.get(0);
        }
        return user;
    }

    /**
     * 在「忽略数据权限」的上下文中执行数据库操作
     *
     * <p>本服务的调用方永远是未登录用户（注册、找回密码），此时数据权限拦截器
     * 解析当前登录用户会得到 null 并抛 NPE：
     * {@code Cannot invoke "LoginUser.getRoles()" because "user" is null}。
     * 匿名场景本就不该套用数据权限，显式忽略即可 —— 与项目自身在
     * {@code SysLoginService} 中记录登录信息时的处理方式一致。
     */
    private <T> T anonymous(java.util.function.Supplier<T> action) {
        return DataPermissionHelper.ignore(action);
    }

    /**
     * 验证码相关 Redis 键：以 userId 为准
     *
     * <p>不用用户输入的账号/邮箱做键 —— 同一个用户可能这次用账号申请、下次用邮箱重置，
     * 那样会对不上；用 userId 既能统一，也避免把账号信息写进 Redis 键。
     */
    private String userKey(SysUser user) {
        return String.valueOf(user.getUserId());
    }

    /**
     * 读取键的剩余存活秒数
     *
     * <p>Redisson 的 {@code remainTimeToLive()} 返回毫秒，需要换算；
     * 键不存在（-2）或无过期时间（-1）时回落到完整间隔，避免提示出负数。
     */
    private long remainSeconds(String key) {
        long millis = RedisUtils.getTimeToLive(key);
        if (millis <= 0) {
            return SEND_INTERVAL_SECONDS;
        }
        return Math.max(millis / 1000, 1L);
    }

    /** 邮箱脱敏：zhangsan@a.com → zh****@a.com */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String name = email.substring(0, at);
        String domain = email.substring(at);
        if (name.length() <= 2) {
            return name.charAt(0) + "***" + domain;
        }
        return name.substring(0, 2) + "****" + domain;
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }
}

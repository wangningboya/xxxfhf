package com.xxxfhf.meal.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.role.Role;
import com.xxxfhf.meal.role.RoleMapper;
import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import com.xxxfhf.meal.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 鉴权服务：群成员走微信，商家走账密。
 *
 * 账号类型由 user.user_type 决定（customer / merchant），角色只决定「能做什么」。
 *
 * 边界说明：
 * - 微信侧只做「code2Session → 落/取用户 → 发 token」，首次进入即为 pending（待审核）。
 * - 准入（群 openGId / 邀请码）由 {@link GroupAccessService} 负责，本类只把结果拼进响应。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final WxApiClient wxApiClient;
    private final SessionKeyStore sessionKeyStore;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final JwtService jwtService;
    private final PasswordService passwordService;
    private final GroupAccessService groupAccessService;

    public AuthService(WxApiClient wxApiClient, SessionKeyStore sessionKeyStore,
                       UserMapper userMapper, RoleMapper roleMapper,
                       JwtService jwtService, PasswordService passwordService,
                       GroupAccessService groupAccessService) {
        this.wxApiClient = wxApiClient;
        this.sessionKeyStore = sessionKeyStore;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.jwtService = jwtService;
        this.passwordService = passwordService;
        this.groupAccessService = groupAccessService;
    }

    @Transactional
    public LoginResult login(String code) {
        WxApiClient.WxSession session = wxApiClient.code2Session(code);

        // session_key 只留在服务端内存，用于解密 openGId 与手机号
        sessionKeyStore.put(session.openid(), session.sessionKey());

        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getOpenid, session.openid()));

        if (user == null) {
            user = new User();
            user.setUserType(UserType.CUSTOMER);
            user.setOpenid(session.openid());
            user.setAuditStatus(AuditStatus.PENDING);
            user.setRoleIds(List.of());
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
            log.info("新用户登记待审核 userId={}", user.getId());
        }

        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            // 禁用用户仍签发 token 没有意义，直接拒绝
            throw BizException.forbidden("账号已被禁用，请联系群管理员");
        }

        String token = jwtService.issue(user.getId(), user.getUserType());
        GroupAccessService.AccessState access = groupAccessService.resolve(user);
        return new LoginResult(token, user.getId(), user.getNickName(), user.getAvatarUrl(),
                access.accessGranted(), access.accessVia(),
                access.auditStatus(), access.rejectReason(), access.phone());
    }

    /** 当前登录用户的准入与审核状态。前端每次进「我的」页或从后台切回来时刷新。 */
    @Transactional(readOnly = true)
    public MeResult me(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("登录已失效，请重新进入小程序");
        }
        GroupAccessService.AccessState access = groupAccessService.resolve(user);
        return new MeResult(user.getId(), user.getNickName(), user.getAvatarUrl(),
                access.accessGranted(), access.accessVia(),
                access.auditStatus(), access.rejectReason(), access.phone());
    }

    /**
     * 商家账密登录（网页后台）。
     *
     * 只认 user_type = merchant 的账号；群成员没有密码，不可能从这里登进来。
     * 账号不存在与密码错误返回同一句提示，避免被用来试探哪些账号存在。
     */
    @Transactional(readOnly = true)
    public AdminLoginResult adminLogin(String username, String rawPassword) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .eq(User::getUserType, UserType.MERCHANT));

        if (user == null || !passwordService.matches(rawPassword, user.getPasswordHash())) {
            log.warn("商家登录失败，账号或密码错误 username={}", username);
            throw BizException.unauthorized("账号或密码错误");
        }
        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            throw BizException.forbidden("账号已停用，请联系管理员");
        }

        Set<String> permissions = resolvePermissions(user.getRoleIds());
        String token = jwtService.issue(user.getId(), UserType.MERCHANT);
        log.info("商家登录成功 userId={} username={} 权限数={}", user.getId(), username, permissions.size());

        String displayName = user.getNickName() == null || user.getNickName().isBlank()
                ? username : user.getNickName();
        return new AdminLoginResult(token, user.getId(), username, displayName, List.copyOf(permissions));
    }

    /**
     * 网页后台刷新页面后恢复登录态。
     *
     * 令牌里不携带权限（权限被收回要即时生效），所以刷新后必须重新从库里聚合一次，
     * 否则前端只能拿登录那一刻的旧菜单，改完角色要重新登录才看得到。
     */
    @Transactional(readOnly = true)
    public AdminMeResult adminMe(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("登录已失效，请重新登录");
        }
        if (!UserType.MERCHANT.equals(user.getUserType())) {
            throw BizException.forbidden("该账号不是商家账号");
        }
        String displayName = user.getNickName() == null || user.getNickName().isBlank()
                ? user.getUsername() : user.getNickName();
        return new AdminMeResult(user.getId(), user.getUsername(), displayName,
                List.copyOf(resolvePermissions(user.getRoleIds())));
    }

    /**
     * 由角色聚合权限。角色为空则权限为空——商家账号没配角色时登进去是白屏，
     * 这是配置问题，不该由代码兜底给全权限。
     */
    private Set<String> resolvePermissions(List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> permissions = new LinkedHashSet<>();
        for (String roleId : roleIds) {
            Role role = roleMapper.selectById(roleId);
            if (role != null && role.getPermissions() != null) {
                permissions.addAll(role.getPermissions());
            }
        }
        return permissions;
    }

    /**
     * 登录结果。
     *
     * @param token         登录令牌
     * @param accessGranted 是否已取得准入资格（是不是本店群里的人）
     * @param accessVia     group / invite / null
     * @param auditStatus   pending / approved / rejected，前端据此决定下单按钮是否可点
     * @param rejectReason  驳回原因，仅 rejected 时有值
     * @param phone         已采集的手机号，可能为空
     */
    public record LoginResult(String token, String userId, String nickName, String avatarUrl,
                              boolean accessGranted, String accessVia,
                              String auditStatus, String rejectReason, String phone) {
    }

    /** 「我的」页与拦截页用的当前用户状态，字段含义同 LoginResult。 */
    public record MeResult(String userId, String nickName, String avatarUrl,
                           boolean accessGranted, String accessVia,
                           String auditStatus, String rejectReason, String phone) {
    }

    /**
     * @param token       登录令牌（user_type = merchant）
     * @param permissions 权限项列表，后台据此显隐菜单；接口是否放行仍以服务端为准
     */
    public record AdminLoginResult(String token, String userId, String username,
                                   String displayName, List<String> permissions) {
    }

    /** 商家后台刷新后的登录态，与 AdminLoginResult 相比少了 token（令牌还在，不必重发）。 */
    public record AdminMeResult(String userId, String username, String displayName,
                                List<String> permissions) {
    }
}

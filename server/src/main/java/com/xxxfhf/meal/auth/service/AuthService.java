package com.xxxfhf.meal.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.audit.domain.AuditActions;
import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.auth.SessionKeyStore;
import com.xxxfhf.meal.auth.WxApiClient;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.config.AppProperties;
import com.xxxfhf.meal.role.domain.Role;
import com.xxxfhf.meal.role.mapper.RoleMapper;
import com.xxxfhf.meal.user.domain.AuditStatus;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.domain.UserType;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
 * - 微信侧分两步：{@link #login} 只做「code2Session → 取用户 → 发 token」，查到就登录、查不到就说没登记；
 *   {@link #join} 才是建号的唯一入口，且必须先拿到准入凭证（邀请码或群卡片）。
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
    private final AuditService auditService;
    /** 登录失败锁定阈值与时长走环境变量（APP_AUTH_LOGIN_LOCK_*），属安全闸门不让商家调 */
    private final AppProperties appProperties;

    public AuthService(WxApiClient wxApiClient, SessionKeyStore sessionKeyStore,
                       UserMapper userMapper, RoleMapper roleMapper,
                       JwtService jwtService, PasswordService passwordService,
                       GroupAccessService groupAccessService, AuditService auditService,
                       AppProperties appProperties) {
        this.wxApiClient = wxApiClient;
        this.sessionKeyStore = sessionKeyStore;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.jwtService = jwtService;
        this.passwordService = passwordService;
        this.groupAccessService = groupAccessService;
        this.auditService = auditService;
        this.appProperties = appProperties;
    }

    @Transactional
    public LoginResult login(String code) {
        WxApiClient.WxSession session = wxApiClient.code2Session(code);

        // session_key 只留在服务端内存，用于解密 openGId 与手机号
        sessionKeyStore.put(session.openid(), session.sessionKey());

        User user = findByOpenid(session.openid());
        if (user == null) {
            // 没登记过就不登记：建号只发生在 join()，见那里的说明。
            // 这里返回一个空壳结果，前端据此去拉群卡片或让用户填邀请码。
            log.info("已识别的微信用户尚未登记，等待准入凭证");
            return LoginResult.unregistered();
        }

        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            // 禁用用户仍签发 token 没有意义，直接拒绝
            throw BizException.forbidden("账号已被禁用，请联系群管理员");
        }

        String token = jwtService.issue(user.getId(), user.getUserType(), tokenVersion(user));
        GroupAccessService.AccessState access = groupAccessService.resolve(user);
        return new LoginResult(token, user.getId(), user.getNickName(), user.getAvatarUrl(),
                access.accessGranted(), access.accessVia(),
                access.auditStatus(), access.rejectReason(), access.phone(), true);
    }

    /**
     * 首次进入：凭邀请码或群卡片登记账号并登录。
     *
     * <p>为什么建号不在 {@link #login} 里做：登录只凭 code 换 openid，任何人打开小程序都会调一次。
     * 顺手建号的后果是「打开过小程序」的人全部堆进商家的待审核列表，注销之后重新打开又冒出一条新的
     * （小程序注销成功会重进首页，首页 onShow 必然触发一次登录）。所以建号必须挂在一个
     * 「确实取得了准入资格」的动作上：邀请码被成功核销，或群 openGId 通过白名单校验。
     *
     * <p>顺序是「先插入 → 再核销」而不是反过来：邀请码核销要写使用明细，必须先有 userId。
     * 两步在同一个事务里，凭证无效时刚插入的行跟着回滚，库里不会留下谁也用不上的空账号。
     *
     * @param code          微信登录 code
     * @param rawInviteCode 邀请码，与群卡片二选一
     * @param encryptedData 群卡片密文，与邀请码二选一
     * @param iv            群卡片向量，随 encryptedData 一起给
     */
    @Transactional
    public LoginResult join(String code, String rawInviteCode, String encryptedData, String iv) {
        boolean byInvite = rawInviteCode != null && !rawInviteCode.isBlank();
        boolean byGroup = encryptedData != null && !encryptedData.isBlank()
                && iv != null && !iv.isBlank();
        if (byInvite == byGroup) {
            // 两条都给或都不给：分不清该用哪条路，宁可报错也不猜
            throw BizException.param("请提供邀请码或群卡片信息");
        }

        WxApiClient.WxSession session = wxApiClient.code2Session(code);
        sessionKeyStore.put(session.openid(), session.sessionKey());

        User user = findByOpenid(session.openid());
        if (user == null) {
            user = new User();
            user.setUserType(UserType.CUSTOMER);
            user.setOpenid(session.openid());
            user.setAuditStatus(AuditStatus.PENDING);
            user.setRoleIds(List.of());
            user.setCreatedAt(LocalDateTime.now());
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException ex) {
                // 同一 openid 并发提交了两次：唯一索引挡住后一条，用先落库的那条继续
                user = findByOpenid(session.openid());
            }
            if (user == null || user.getId() == null) {
                throw new BizException(ErrorCode.SERVER_ERROR, "登记失败，请重试");
            }
            log.info("新用户凭准入凭证登记 userId={}", user.getId());
        }

        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            throw BizException.forbidden("账号已被禁用，请联系群管理员");
        }

        // 凭证校验失败会抛异常，上面插入的行跟着回滚——这是「脏数据不再产生」的关键
        GroupAccessService.AccessState access = byInvite
                ? groupAccessService.bindByInvite(user, rawInviteCode)
                : groupAccessService.bindByShare(user, encryptedData, iv);

        String token = jwtService.issue(user.getId(), user.getUserType(), tokenVersion(user));
        return new LoginResult(token, user.getId(), user.getNickName(), user.getAvatarUrl(),
                access.accessGranted(), access.accessVia(),
                access.auditStatus(), access.rejectReason(), access.phone(), true);
    }

    /** 按 openid 查用户；未登记返回 null。 */
    private User findByOpenid(String openid) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getOpenid, openid));
    }

    /** 当前登录用户的准入与审核状态。前端每次进「我的」页或从后台切回来时刷新。 */
    @Transactional(readOnly = true)
    public MeResult me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("登录已失效，请重新进入小程序");
        }
        GroupAccessService.AccessState access = groupAccessService.resolve(user);
        return new MeResult(user.getId(), user.getNickName(), user.getAvatarUrl(),
                access.accessGranted(), access.accessVia(),
                access.auditStatus(), access.rejectReason(), access.phone(),
                user.getUserType());
    }

    /**
     * 本人手机号明文（仅供本人使用）。
     *
     * <p>存在的原因只有一个：改号弹窗和新增地址的预填需要把人自己的号填回输入框。
     * {@code /auth/me} 里故意只给掩码，否则「登录态响应」就是整包明文，抓一次包全都有了。
     * 这里只返回当前令牌对应的那一条，不接收 userId 参数，也就没有越权取号的可能。
     *
     * <p>「本人查自己」PIPL 不强制留痕，但明文出口统一记 {@code user.phone.view}，
     * 与管理端 {@code fullPhone} 保持同一条审计口径，合规闭环。审计摘要里只放掩码，
     * 明文号码不进任何日志。
     *
     * <p>注意事务不能是 {@code readOnly}：审计要插行，只读事务会让记录静默失败。
     */
    @Transactional
    public String myPhone(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("登录已失效，请重新进入小程序");
        }
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_PHONE_VIEW,
                "本人查看完整手机号：" + MaskUtil.phone(user.getPhone()));
        return user.getPhone();
    }

    /**
     * 商家账密登录（网页后台）。
     *
     * 只认 user_type = merchant 的账号；群成员没有密码，不可能从这里登进来。
     * 账号不存在与密码错误返回同一句提示，避免被用来试探哪些账号存在。
     *
     * 登录失败锁定（防暴力穷举）：连续失败 3 次锁 30 分钟，期间即使密码正确也拒绝；
     * 到期自动解锁，管理员也可在「账号授权」页提前解除。锁定状态落库，重启不丢。
     */
    @Transactional
    public AdminLoginResult adminLogin(String username, String rawPassword) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .eq(User::getUserType, UserType.MERCHANT));

        // 锁定中直接拒绝。不先验密码：不能让穷举者靠报错差异猜出「密码其实是对的」
        if (user != null && user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            long minutes = Math.max(1, Duration.between(LocalDateTime.now(), user.getLockUntil()).toMinutes());
            log.warn("商家登录被拒：账号锁定中 username={} 剩余约 {} 分钟", username, minutes);
            throw BizException.tooMany("失败次数过多，账号已锁定，约 " + minutes + " 分钟后自动解锁，或由管理员在「账号授权」页解除");
        }

        if (user == null || !passwordService.matches(rawPassword, user.getPasswordHash())) {
            log.warn("商家登录失败，账号或密码错误 username={}", username);
            // 账号存在才计数；不存在的账号无处落锁定状态，靠 nginx 限流挡穷举
            if (user != null) {
                recordLoginFailure(user);
            }
            throw BizException.unauthorized("账号或密码错误");
        }
        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            throw BizException.forbidden("账号已停用，请联系管理员");
        }

        // 登录成功清零计数与锁定。用 LambdaUpdate 而非 updateById：后者跳过 null 字段，lock_until 清不掉
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getLoginFailCount, 0)
                .set(User::getLockUntil, null));

        Set<String> permissions = resolvePermissions(user.getRoleIds());
        String token = jwtService.issue(user.getId(), UserType.MERCHANT, tokenVersion(user));
        log.info("商家登录成功 userId={} username={} 权限数={}", user.getId(), username, permissions.size());

        String displayName = user.getNickName() == null || user.getNickName().isBlank()
                ? username : user.getNickName();
        return new AdminLoginResult(token, user.getId(), username, displayName, List.copyOf(permissions));
    }

    /** 版本号统一取值：新建用户实体未填充时按 0，与库列默认值一致。 */
    private static int tokenVersion(User user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /** 连续失败计数：到阈值（默认 3 次）锁 M 分钟（默认 30）并清零计数，未到阈值则累加。 */
    private void recordLoginFailure(User user) {
        int threshold = appProperties.getAuth().getLoginLock().getMaxFailures();
        long lockMinutes = appProperties.getAuth().getLoginLock().getMinutes();
        int fails = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        if (fails >= threshold) {
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, user.getId())
                    .set(User::getLoginFailCount, 0)
                    .set(User::getLockUntil, LocalDateTime.now().plusMinutes(lockMinutes)));
            log.warn("商家账号连续登录失败 {} 次，锁定 {} 分钟 userId={}",
                    threshold, lockMinutes, user.getId());
        } else {
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, user.getId())
                    .set(User::getLoginFailCount, fails));
        }
    }

    /**
     * 网页后台刷新页面后恢复登录态。
     *
     * 令牌里不携带权限（权限被收回要即时生效），所以刷新后必须重新从库里聚合一次，
     * 否则前端只能拿登录那一刻的旧菜单，改完角色要重新登录才看得到。
     */
    @Transactional(readOnly = true)
    public AdminMeResult adminMe(Long userId) {
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
    private Set<String> resolvePermissions(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> permissions = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
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
     * @param registered    false 表示这个微信还没登记过，token 与其余字段全为空，
     *                      前端应引导用户凭邀请码或群卡片进入（POST /auth/join）
     */
    public record LoginResult(String token, Long userId, String nickName, String avatarUrl,
                              boolean accessGranted, String accessVia,
                              String auditStatus, String rejectReason, String phone,
                              boolean registered) {

        /** 未登记：除 registered=false 外全为空。 */
        public static LoginResult unregistered() {
            return new LoginResult(null, null, null, null, false, null, null, null, null, false);
        }
    }

    /**
     * 「我的」页与拦截页用的当前用户状态，字段含义同 LoginResult。
     *
     * @param userType customer / merchant，取自库（不写进令牌），前端据此决定「我的」宫格是否出现商家工作台
     */
    public record MeResult(Long userId, String nickName, String avatarUrl,
                           boolean accessGranted, String accessVia,
                           String auditStatus, String rejectReason, String phone,
                           String userType) {
    }

    /**
     * @param token       登录令牌（user_type = merchant）
     * @param permissions 权限项列表，后台据此显隐菜单；接口是否放行仍以服务端为准
     */
    public record AdminLoginResult(String token, Long userId, String username,
                                   String displayName, List<String> permissions) {
    }

    /** 商家后台刷新后的登录态，与 AdminLoginResult 相比少了 token（令牌还在，不必重发）。 */
    public record AdminMeResult(Long userId, String username, String displayName,
                                List<String> permissions) {
    }
}

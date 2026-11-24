package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ContentSecurityService;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.common.NickNameValidator;
import com.xxxfhf.meal.common.PhoneValidator;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.invite.InviteAttemptGuard;
import com.xxxfhf.meal.invite.InviteCodeService;
import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 准入：群成员判定、openGId 绑定、邀请码准入、昵称与手机号采集。
 *
 * 两条准入路径：
 * 1. 群卡片（主路径）：解出 openGId → 校验在本店登记的群白名单内 → 绑定到 user.groupOpenId
 * 2. 邀请码（正式路径）：核销扣一次次数 → 记 invite_use 明细
 *
 * 「准入」与「审核」是两件事：
 * - 准入 = 是不是本店群里的人（决定能不能进小程序、能不能看到菜单）
 * - 审核 = 商家认不认这个人（决定能不能下单）
 * 已准入但待审核的用户按要求可以浏览菜单，只是下单按钮不可用。
 *
 * <p>建号的唯一入口是 {@link AuthService#join}：那里先插入 pending 行再调本类校验凭证，
 * 校验不过就抛异常让整个事务回滚。所以「打开过小程序」本身不会在库里留下任何记录。
 */
@Service
public class GroupAccessService {

    private static final Logger log = LoggerFactory.getLogger(GroupAccessService.class);

    private final UserMapper userMapper;
    private final SessionKeyStore sessionKeyStore;
    private final WxCrypto wxCrypto;
    private final ConfigService configService;
    private final InviteCodeService inviteCodeService;
    private final InviteAttemptGuard inviteAttemptGuard;
    private final AuditService auditService;
    private final ContentSecurityService contentSecurity;

    public GroupAccessService(UserMapper userMapper, SessionKeyStore sessionKeyStore, WxCrypto wxCrypto,
                              ConfigService configService, InviteCodeService inviteCodeService,
                              InviteAttemptGuard inviteAttemptGuard,
                              AuditService auditService, ContentSecurityService contentSecurity) {
        this.userMapper = userMapper;
        this.sessionKeyStore = sessionKeyStore;
        this.wxCrypto = wxCrypto;
        this.configService = configService;
        this.inviteCodeService = inviteCodeService;
        this.inviteAttemptGuard = inviteAttemptGuard;
        this.auditService = auditService;
        this.contentSecurity = contentSecurity;
    }

    /* ---------------- 准入判定 ---------------- */

    /**
     * 计算当前用户的准入状态。登录与「我的」页刷新都调这里。
     *
     * 判定顺序：已绑群 > 用过邀请码 > 未准入。
     */
    public AccessState resolve(User user) {
        String via = null;
        if (user.getGroupOpenId() != null && !user.getGroupOpenId().isBlank()) {
            via = AccessVia.GROUP;
        } else if (inviteCodeService.hasConsumed(user.getId())) {
            via = AccessVia.INVITE;
        }
        // 手机号一律掩码出网关：登录态与「我的」页都不需要 11 位明文，
        // 需要全号的地方（本人改号、地址预填）走 /auth/my-phone 单独取
        return new AccessState(via != null, via, user.getAuditStatus(),
                user.getRejectReason(), MaskUtil.phone(user.getPhone()));
    }

    /* ---------------- 路径一：群卡片 ---------------- */

    /**
     * 用群卡片带回来的加密数据绑定 openGId。
     *
     * @param userId        当前登录用户
     * @param encryptedData wx.getShareInfo / wx.getGroupEnterInfo 返回的密文
     * @param iv            同上返回的向量
     */
    @Transactional
    public AccessState bindByShare(Long userId, String encryptedData, String iv) {
        return bindByShare(requireUser(userId), encryptedData, iv);
    }

    /**
     * 同 {@link #bindByShare(Long, String, String)}，直接作用于一个用户行。
     *
     * <p>给 {@link AuthService#join} 用：首次进入时用户行刚在同一个事务里插入，还没有提交，
     * 只能把实体传进来而不是再按 id 查一次。建号与绑定必须同生共死——
     * 群卡片不是本店的群时，刚插入的行要跟着回滚，否则又回到「打开过小程序就多一条待审核记录」。
     */
    @Transactional
    public AccessState bindByShare(User user, String encryptedData, String iv) {
        if (user.getGroupOpenId() != null && !user.getGroupOpenId().isBlank()) {
            // 已绑定就是已准入，重复进来不必再解一次密
            return resolve(user);
        }
        if (user.getOpenid() == null || user.getOpenid().isBlank()) {
            throw BizException.forbidden("当前账号不是微信用户，无法校验群");
        }

        String sessionKey = sessionKeyStore.get(user.getOpenid()).orElse(null);
        if (sessionKey == null) {
            // session_key 只存在服务端内存（7 天 TTL）：服务一重启就没了。
            // 这是「群卡片点了没反应」最常见的原因，必须留一条能对照的日志
            log.warn("群凭证解析失败：session_key 已失效（服务可能重启过，重新进入小程序即可） userId={}",
                    user.getId());
            throw BizException.unauthorized("登录状态已过期，请重新进入小程序");
        }

        log.info("收到群卡片凭证 userId={} 密文长度={}",
                user.getId(), encryptedData == null ? 0 : encryptedData.length());
        String openGId = wxCrypto.requireOpenGId(sessionKey, encryptedData, iv);

        List<String> allowed = configService.getStringList(ConfigKey.GROUP_OPEN_IDS);
        if (allowed.isEmpty()) {
            // 商家还没登记任何群：明确告知，不要含糊成「校验失败」
            log.warn("群准入被拒：groupOpenIds 未配置，openGId={}", openGId);
            throw BizException.forbidden("本店尚未登记任何微信群，请使用邀请码进入");
        }
        if (!allowed.contains(openGId)) {
            // 群卡片被转发到别的群时的正常拦截
            log.warn("群准入被拒：openGId 不在白名单，openGId={} 用户={}", openGId, user.getId());
            throw BizException.forbidden("该群不是本店的服务群，请联系商家获取邀请码");
        }

        user.setGroupOpenId(openGId);
        userMapper.updateById(user);
        log.info("群绑定成功 userId={} openGId={}", user.getId(), openGId);
        return resolve(user);
    }

    /* ---------------- 路径二：邀请码 ---------------- */

    /**
     * 用邀请码取得准入资格。
     *
     * <p>邀请码是商家亲手发出去的：发码就是对这个人点头，所以核销成功即视为审核通过，
     * 不再让人停在待审核里等第二次确认。被驳回的人用新码进来同样直接通过。
     * 被禁用的用户不放行——禁用是商家主动切断，不能被一张码绕过。
     */
    @Transactional
    public AccessState bindByInvite(Long userId, String rawCode) {
        return bindByInvite(requireUser(userId), rawCode);
    }

    /**
     * 同 {@link #bindByInvite(Long, String)}，直接作用于一个用户行。
     *
     * <p>给 {@link AuthService#join} 用：邀请码核销要写使用明细，必须先有 userId，
     * 所以首次进入的顺序是「先插入 pending 行拿 id → 再核销 → 核销失败整个事务回滚」。
     * 反过来的话，一张无效的码就会在库里留下一条谁也用不上的待审核记录。
     */
    @Transactional
    public AccessState bindByInvite(User user, String rawCode) {
        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            throw BizException.forbidden("账号已被禁用，请联系群管理员");
        }
        if (AuditStatus.APPROVED.equals(user.getAuditStatus())) {
            // 已经通过的人再交一次码没有意义；核销掉只会白烧一张，留给下一个人
            return resolve(user);
        }

        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        // 按 openid 限次：首次进入失败时用户行会随事务回滚，userId 下次就变了，只有 openid 稳定。
        // 拿到 openid 前先查一次锁，锁着连库都不碰，也不消耗商家发出的码。
        String attemptKey = user.getOpenid() == null || user.getOpenid().isBlank()
                ? "u:" + user.getId()
                : user.getOpenid();
        inviteAttemptGuard.check(attemptKey);
        try {
            inviteCodeService.consume(code, user.getId());
        } catch (BizException ex) {
            // 只有「码不对/已用/作废/过期」这类业务拒绝才计数；数据库异常不是用户的错
            inviteAttemptGuard.onFailure(attemptKey);
            throw ex;
        }
        inviteAttemptGuard.onSuccess(attemptKey);

        // 核销成功即通过审核。写在这一层而不是各个调用点，是为了让首次建号（/auth/join）
        // 和已登记用户（/auth/invite）共用同一条规则，且都落在调用方的事务里。
        String from = user.getAuditStatus();
        user.setAuditStatus(AuditStatus.APPROVED);
        user.setApprovedAt(LocalDateTime.now());
        user.setRejectReason(null);
        userMapper.updateById(user);
        log.info("凭邀请码进入自动通过审核 userId={} {} -> approved", user.getId(), from);

        // 这是系统代做的动作（请求来自免登录的 /auth/join，没有登录态可用），
        // 所以操作人记 SYSTEM、来源记小程序，摘要里写清是凭哪张码自动通过的
        auditService.record(AuditActions.TABLE_USER, user.getId(), AuditActions.USER_APPROVE,
                "凭邀请码 " + code + " 进入，自动通过审核（" + auditStatusLabel(from) + " → 已通过）",
                AuditHelper.SYSTEM, AuditHelper.SOURCE_MINIPROGRAM);
        return resolve(user);
    }

    /* ---------------- 手机号 ---------------- */

    /**
     * 用户自己改手机号（手填）。
     *
     * 只有这一条路径：商家代填走 /admin/user/phone。不再提供微信一键授权，
     * 省掉手机号快速验证能力的按次费用与隐私声明负担。
     */
    @Transactional
    public String updatePhone(Long userId, String phone) {
        User user = requireUser(userId);
        String normalized = PhoneValidator.require(phone);
        String before = user.getPhone();
        user.setPhone(normalized);
        userMapper.updateById(user);
        // 用户自己改也要留痕：改到别的小区可能就是改配送范围，商家事后要对得上
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_PHONE,
                "用户自行修改手机号｜"
                        + (before == null || before.isBlank() ? "原为空" : before) + " → " + normalized);
        return normalized;
    }

    /* ---------------- 昵称 ---------------- */

    /**
     * 保存用户自己填的昵称。
     *
     * 微信自 2022-10-25 起回收了 wx.getUserProfile 的真实昵称与头像（只返回「微信用户」+ 灰色头像），
     * 服务端拿不到昵称。所以改由小程序的「昵称填写能力」（input type="nickname"）让用户
     * 在输入框里一键点选微信昵称或手动输入，前端拿到明文后提交到这里入库。
     *
     * 昵称只用于商家审核列表辨认人，不做唯一性校验：群里重名很常见，为了唯一而拦人得不偿失。
     */
    @Transactional
    public String updateNickName(Long userId, String rawNickName) {
        User user = requireUser(userId);
        String nickName = NickNameValidator.require(rawNickName);
        // 内容安全：昵称会出现在商家审核列表里，是公开可见的文本，先过一遍微信检测
        if (!contentSecurity.pass(user.getId(), user.getOpenid(), nickName)) {
            throw BizException.param("昵称包含违规内容，请修改");
        }
        user.setNickName(nickName);
        userMapper.updateById(user);
        log.info("用户昵称已更新 userId={}", userId);
        return nickName;
    }

    /* ---------------- 重新申请 ---------------- */

    /**
     * 被驳回后重新提交申请：rejected → pending，清空驳回原因。
     *
     * 仅对 rejected 有效；其它状态直接返回当前状态，不做无意义的报错——
     * 用户手快连点两次不该看到红字。
     */
    @Transactional
    public AccessState reapply(Long userId) {
        User user = requireUser(userId);
        if (!AuditStatus.REJECTED.equals(user.getAuditStatus())) {
            return resolve(user);
        }
        user.setAuditStatus(AuditStatus.PENDING);
        user.setRejectReason(null);
        userMapper.updateById(user);
        log.info("用户重新提交审核申请 userId={}", userId);
        return resolve(user);
    }

    /** 操作记录里指代审核状态。自动通过这条只会出现待审核 / 已驳回，其余兜底保留。 */
    private static String auditStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case AuditStatus.PENDING -> "待审核";
            case AuditStatus.REJECTED -> "已驳回";
            case AuditStatus.DISABLED -> "已停用";
            case AuditStatus.APPROVED -> "已通过";
            default -> "未准入";
        };
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("登录已失效，请重新进入小程序");
        }
        return user;
    }

    /** 准入路径标识，前端据此决定拦截页文案（群卡片失效 vs 从没进过）。 */
    public static final class AccessVia {

        private AccessVia() {
        }

        public static final String GROUP = "group";
        public static final String INVITE = "invite";
    }

    /**
     * @param accessGranted 是否已取得准入资格（与审核状态无关）
     * @param accessVia     group / invite / null（未准入）
     * @param auditStatus   pending / approved / rejected / disabled
     * @param rejectReason  仅 rejected 时有值
     * @param phone         已采集的手机号，可能为空
     */
    public record AccessState(boolean accessGranted, String accessVia, String auditStatus,
                              String rejectReason, String phone) {
    }
}

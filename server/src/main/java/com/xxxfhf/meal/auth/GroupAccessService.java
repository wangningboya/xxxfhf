package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.NickNameValidator;
import com.xxxfhf.meal.common.PhoneValidator;
import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.invite.InviteCodeService;
import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 准入：群成员判定、openGId 绑定、邀请码准入、昵称与手机号采集。
 *
 * 两条准入路径：
 * 1. 群卡片（主路径）：解出 openGId → 校验在本店登记的群白名单内 → 绑定到 user.groupOpenId
 * 2. 邀请码（正式路径）：一次性核销 → 记 invite_code.used_by
 *
 * 「准入」与「审核」是两件事：
 * - 准入 = 是不是本店群里的人（决定能不能进小程序、能不能看到菜单）
 * - 审核 = 商家认不认这个人（决定能不能下单）
 * 已准入但待审核的用户按要求可以浏览菜单，只是下单按钮不可用。
 */
@Service
public class GroupAccessService {

    private static final Logger log = LoggerFactory.getLogger(GroupAccessService.class);

    private final UserMapper userMapper;
    private final SessionKeyStore sessionKeyStore;
    private final WxCrypto wxCrypto;
    private final ConfigService configService;
    private final InviteCodeService inviteCodeService;

    public GroupAccessService(UserMapper userMapper, SessionKeyStore sessionKeyStore, WxCrypto wxCrypto,
                              ConfigService configService, InviteCodeService inviteCodeService) {
        this.userMapper = userMapper;
        this.sessionKeyStore = sessionKeyStore;
        this.wxCrypto = wxCrypto;
        this.configService = configService;
        this.inviteCodeService = inviteCodeService;
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
        return new AccessState(via != null, via, user.getAuditStatus(),
                user.getRejectReason(), user.getPhone());
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
    public AccessState bindByShare(String userId, String encryptedData, String iv) {
        User user = requireUser(userId);

        if (user.getGroupOpenId() != null && !user.getGroupOpenId().isBlank()) {
            // 已绑定就是已准入，重复进来不必再解一次密
            return resolve(user);
        }
        if (user.getOpenid() == null || user.getOpenid().isBlank()) {
            throw BizException.forbidden("当前账号不是微信用户，无法校验群");
        }

        String sessionKey = sessionKeyStore.get(user.getOpenid())
                .orElseThrow(() -> BizException.unauthorized("登录状态已过期，请重新进入小程序"));

        String openGId = wxCrypto.requireOpenGId(sessionKey, encryptedData, iv);

        List<String> allowed = configService.getStringList(ConfigKey.GROUP_OPEN_IDS);
        if (allowed.isEmpty()) {
            // 商家还没登记任何群：明确告知，不要含糊成「校验失败」
            log.warn("群准入被拒：groupOpenIds 未配置，openGId={}", openGId);
            throw BizException.forbidden("本店尚未登记任何微信群，请使用邀请码进入");
        }
        if (!allowed.contains(openGId)) {
            // 群卡片被转发到别的群时的正常拦截
            log.warn("群准入被拒：openGId 不在白名单，openGId={} 用户={}", openGId, userId);
            throw BizException.forbidden("该群不是本店的服务群，请联系商家获取邀请码");
        }

        user.setGroupOpenId(openGId);
        userMapper.updateById(user);
        log.info("群绑定成功 userId={} openGId={}", userId, openGId);
        return resolve(user);
    }

    /* ---------------- 路径二：邀请码 ---------------- */

    /**
     * 用邀请码取得准入资格。
     *
     * 被驳回过的用户用了新的邀请码，视为重新申请：回到待审核、清空驳回原因。
     * 被禁用的用户不放行——禁用是商家主动切断，不能被一张码绕过。
     */
    @Transactional
    public AccessState bindByInvite(String userId, String rawCode) {
        User user = requireUser(userId);

        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            throw BizException.forbidden("账号已被禁用，请联系群管理员");
        }
        if (user.getGroupOpenId() != null && !user.getGroupOpenId().isBlank()) {
            // 已经是群成员，不需要邀请码
            return resolve(user);
        }

        inviteCodeService.consume(rawCode, userId);

        if (AuditStatus.REJECTED.equals(user.getAuditStatus())) {
            user.setAuditStatus(AuditStatus.PENDING);
            user.setRejectReason(null);
            userMapper.updateById(user);
            log.info("被驳回用户用邀请码重新进入，状态回到待审核 userId={}", userId);
        }
        return resolve(user);
    }

    /* ---------------- 手机号 ---------------- */

    /**
     * 微信手机号一键授权。
     *
     * 受配置项 phoneAuthEnabled 控制：商家没开启时直接拒绝，避免前端误以为可用。
     * 手机号只在配送单需要，但这里只负责采集保存，是否必填由下单环节判断。
     */
    @Transactional
    public String bindPhoneByWx(String userId, String encryptedData, String iv) {
        if (!configService.getBoolean(ConfigKey.PHONE_AUTH_ENABLED, false)) {
            throw BizException.forbidden("商家未开启微信手机号授权，请手动填写");
        }
        User user = requireUser(userId);
        if (user.getOpenid() == null || user.getOpenid().isBlank()) {
            throw BizException.forbidden("当前账号不是微信用户");
        }
        String sessionKey = sessionKeyStore.get(user.getOpenid())
                .orElseThrow(() -> BizException.unauthorized("登录状态已过期，请重新进入小程序"));

        String phone = PhoneValidator.require(wxCrypto.requirePhone(sessionKey, encryptedData, iv));
        user.setPhone(phone);
        userMapper.updateById(user);
        log.info("用户手机号已更新 userId={}", userId);
        return phone;
    }

    /** 用户自己改手机号（手填）。 */
    @Transactional
    public String updatePhone(String userId, String phone) {
        User user = requireUser(userId);
        String normalized = PhoneValidator.require(phone);
        user.setPhone(normalized);
        userMapper.updateById(user);
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
    public String updateNickName(String userId, String rawNickName) {
        User user = requireUser(userId);
        String nickName = NickNameValidator.require(rawNickName);
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
    public AccessState reapply(String userId) {
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

    private User requireUser(String userId) {
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

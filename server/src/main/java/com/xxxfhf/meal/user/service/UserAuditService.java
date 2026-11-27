package com.xxxfhf.meal.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.address.domain.Address;
import com.xxxfhf.meal.address.mapper.AddressMapper;
import com.xxxfhf.meal.audit.domain.AuditActions;
import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.auth.service.PasswordService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.common.PhoneValidator;
import com.xxxfhf.meal.invite.domain.InviteUse;
import com.xxxfhf.meal.invite.mapper.InviteUseMapper;
import com.xxxfhf.meal.message.domain.NoticeType;
import com.xxxfhf.meal.message.service.MessageService;
import com.xxxfhf.meal.order.domain.Order;
import com.xxxfhf.meal.order.mapper.OrderMapper;
import com.xxxfhf.meal.role.domain.Role;
import com.xxxfhf.meal.role.mapper.RoleMapper;
import com.xxxfhf.meal.user.domain.AuditAction;
import com.xxxfhf.meal.user.domain.AuditStatus;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.domain.UserType;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户审核与用户管理（商家端）。
 *
 * 只处理 user_type = customer 的群成员；商家账号不参与审核流程，也不会出现在待审核列表里。
 */
@Service
public class UserAuditService {

    private static final Logger log = LoggerFactory.getLogger(UserAuditService.class);

    private final UserMapper userMapper;
    private final InviteUseMapper inviteUseMapper;
    private final AddressMapper addressMapper;
    private final OrderMapper orderMapper;
    private final AuditService auditService;
    private final PasswordService passwordService;
    private final RoleMapper roleMapper;
    private final MessageService messageService;

    public UserAuditService(UserMapper userMapper, InviteUseMapper inviteUseMapper,
                            AddressMapper addressMapper, OrderMapper orderMapper,
                            AuditService auditService, PasswordService passwordService,
                            RoleMapper roleMapper, MessageService messageService) {
        this.userMapper = userMapper;
        this.inviteUseMapper = inviteUseMapper;
        this.addressMapper = addressMapper;
        this.orderMapper = orderMapper;
        this.auditService = auditService;
        this.passwordService = passwordService;
        this.roleMapper = roleMapper;
        this.messageService = messageService;
    }

    /**
     * 用户列表，供商家审核用。
     *
     * @param auditStatus 为空则查全部；pending / approved / rejected / disabled
     * @param keyword     昵称或手机号模糊匹配，为空不筛
     */
    public IPage<UserRow> list(String auditStatus, String keyword, int page, int pageSize) {
        Page<User> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));

        String kw = keyword == null ? "" : keyword.trim();
        IPage<User> result = userMapper.selectPage(pager, Wrappers.<User>lambdaQuery()
                .eq(User::getUserType, UserType.CUSTOMER)
                .eq(auditStatus != null && !auditStatus.isBlank(), User::getAuditStatus, auditStatus)
                .and(!kw.isEmpty(), w -> w.like(User::getNickName, kw).or().like(User::getPhone, kw))
                // 按业务优先级排：待审核最先，其次驳回待处理，再停用，最后已通过。
                // 字母序排不出这个顺序（approved 会跑到最前），所以用 FIELD()。
                // 这里的 SQL 片段是常量，不含任何用户输入。
                .last("ORDER BY FIELD(audit_status, 'pending', 'rejected', 'disabled', 'approved'), created_at DESC"));

        Set<Long> inviteUsers = inviteUserIds(result.getRecords());
        List<UserRow> rows = new ArrayList<>(result.getRecords().size());
        for (User user : result.getRecords()) {
            rows.add(toRow(user, inviteUsers.contains(user.getId())));
        }

        Page<UserRow> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(rows);
        return mapped;
    }

    /**
     * 批量查本页用户里谁用过邀请码，避免逐行查邀请码明细（N+1）。
     *
     * 这里用字符串列名而不是 Lambda：本方法在单元测试里也会被触达，
     * Lambda 需要 MyBatis-Plus 的 TableInfo 已初始化，纯单测环境拿不到。
     */
    private Set<Long> inviteUserIds(List<User> users) {
        List<Long> ids = users.stream().map(User::getId).toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<InviteUse> uses = inviteUseMapper.selectList(Wrappers.<InviteUse>query()
                .select("user_id")
                .in("user_id", ids));
        Set<Long> set = new HashSet<>(uses.size());
        for (InviteUse use : uses) {
            if (use.getUserId() != null) {
                set.add(use.getUserId());
            }
        }
        return set;
    }

    /**
     * 执行审核动作。
     *
     * @param action approve / reject / disable / enable
     * @param reason 仅 reject 必填
     */
    @Transactional
    public UserRow audit(Long operatorId, Long userId, String action, String reason) {
        User user = requireCustomer(userId);

        String from = user.getAuditStatus();
        String to = switch (action == null ? "" : action) {
            case AuditAction.APPROVE -> {
                requireStatus(from, "审核通过", AuditStatus.PENDING, AuditStatus.REJECTED);
                requireNickName(user);
                user.setApprovedAt(LocalDateTime.now());
                user.setRejectReason(null);
                yield AuditStatus.APPROVED;
            }
            case AuditAction.REJECT -> {
                requireStatus(from, "驳回", AuditStatus.PENDING, AuditStatus.REJECTED);
                requireNickName(user);
                String trimmed = reason == null ? "" : reason.trim();
                if (trimmed.isEmpty()) {
                    throw BizException.param("驳回必须填写原因，用户端会展示该原因");
                }
                if (trimmed.length() > 200) {
                    throw BizException.param("驳回原因最多 200 字");
                }
                user.setRejectReason(trimmed);
                yield AuditStatus.REJECTED;
            }
            case AuditAction.DISABLE -> {
                requireStatus(from, "禁用", AuditStatus.PENDING, AuditStatus.APPROVED, AuditStatus.REJECTED);
                yield AuditStatus.DISABLED;
            }
            case AuditAction.ENABLE -> {
                requireStatus(from, "恢复", AuditStatus.DISABLED);
                user.setApprovedAt(LocalDateTime.now());
                user.setRejectReason(null);
                yield AuditStatus.APPROVED;
            }
            default -> throw BizException.param("未知的审核动作：" + action);
        };

        user.setAuditStatus(to);
        userMapper.updateById(user);
        log.info("用户审核 userId={} {} -> {} 操作人={}", userId, from, to, operatorId);

        recordAudit(user, from, to, reason);
        // 审核结果必须主动告诉用户（v3.50）：建号后要等商家审核，通过了他不知道就不来；
        // 驳回了不告诉他原因，他只会再提一次同样的资料。只落站内，不推订阅
        notifyAuditResult(user, to, reason);

        Set<Long> inviteUsers = inviteUserIds(List.of(user));
        return toRow(user, inviteUsers.contains(user.getId()));
    }

    /** 审核动作 → 操作记录动作码。单独成方法，避免审核流程里再堆一层分支。 */
    private void recordAudit(User user, String from, String to, String reason) {
        String action = switch (to) {
            case AuditStatus.APPROVED -> AuditActions.USER_APPROVE;
            case AuditStatus.REJECTED -> AuditActions.USER_REJECT;
            case AuditStatus.DISABLED -> AuditActions.USER_DISABLE;
            default -> AuditActions.USER_ENABLE;
        };
        StringBuilder summary = new StringBuilder();
        summary.append(switch (to) {
            case AuditStatus.APPROVED -> "审核通过用户";
            case AuditStatus.REJECTED -> "驳回用户";
            case AuditStatus.DISABLED -> "停用用户";
            default -> "恢复用户";
        });
        summary.append('：').append(display(user))
                .append('（').append(label(from)).append(" → ").append(label(to)).append('）');
        if (AuditStatus.REJECTED.equals(to) && reason != null && !reason.isBlank()) {
            summary.append("｜原因：").append(reason.trim());
        }
        auditService.record(AuditActions.TABLE_USER, user.getId(), action, summary.toString());
    }

    /**
     * 审核结果 → 用户站内消息（v3.50）。
     *
     * <p>只处理「通过 / 驳回」两种结果：停用与恢复不在这里发（停用的用户进不来，
     * 说明改在登录拦截文案上）。消息不带订单，走 {@code MessageService.recordToUser}。
     */
    private void notifyAuditResult(User user, String to, String reason) {
        if (AuditStatus.APPROVED.equals(to)) {
            messageService.recordToUser(user.getId(), NoticeType.USER_APPROVED, "审核已通过",
                    "你的账号已通过商家审核，现在可以选餐下单了。菜单每天 0 点后开放次日预订");
        } else if (AuditStatus.REJECTED.equals(to)) {
            String why = reason == null || reason.isBlank() ? "" : "：原因：" + reason.trim();
            messageService.recordToUser(user.getId(), NoticeType.USER_REJECTED, "审核未通过",
                    "你的账号未通过审核" + why + "。请修改资料后重新提交，或联系商家");
        }
    }

    /** 操作记录里指代一个用户：昵称 + 掩码手机号。开头 3 位 + 尾 4 位够认出是谁，拼不出完整号 */
    private static String display(User user) {
        String name = displayName(user);
        return user.getPhone() == null || user.getPhone().isBlank()
                ? name : name + " " + MaskUtil.phone(user.getPhone());
    }

    /** 只要昵称；没填昵称就退回 userId。删除类记录用这个，号码不落库 */
    static String displayName(User user) {
        return user.getNickName() == null || user.getNickName().isBlank()
                ? String.valueOf(user.getId()) : user.getNickName();
    }

    /** 日志里指到某一行用：主键是自增 BIGINT，直接转字符串即可 */
    static String idTail(Long userId) {
        return userId == null ? "" : String.valueOf(userId);
    }

    /**
     * 查看完整手机号（带权限 + 留痕）。
     *
     * <p>列表里只给掩码，真要拨号 / 核对才走这里：一次一个用户、一次一条记录。
     * 这是「能看」与「随便能拉全表」的分界线——后者才是真正会出事的那种泄露。
     */
    public String fullPhone(Long operatorId, Long userId) {
        User user = requireCustomer(userId);
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_PHONE_VIEW,
                "查看完整手机号：" + displayName(user) + "｜操作人：" + operatorId);
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw BizException.notFound("该用户还没填手机号");
        }
        return user.getPhone();
    }

    /** 商家代填 / 修改用户手机号（用户没填或填错时用）。 */
    @Transactional
    public UserRow updatePhone(Long operatorId, Long userId, String phone) {
        User user = requireCustomer(userId);
        String normalized = PhoneValidator.require(phone);
        String before = user.getPhone();
        user.setPhone(normalized);
        userMapper.updateById(user);
        log.info("商家代填手机号 userId={} 操作人={}", userId, operatorId);
        // 摘要里只留掩码：audit_log 是长期留存的，写明文等于把脱敏又绕回去了
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_PHONE,
                "商家修改手机号：" + displayName(user) + "｜原号："
                        + (before == null || before.isBlank() ? "空" : MaskUtil.phoneShort(before))
                        + " → " + MaskUtil.phoneShort(normalized));
        Set<Long> inviteUsers = inviteUserIds(List.of(user));
        return toRow(user, inviteUsers.contains(user.getId()));
    }

    /**
     * 物理删除用户，并连带删掉他名下的收货地址（需求 4.17「删除用户」）。
     *
     * <p>两条硬约束：
     * <ul>
     *   <li><b>有订单就拒删</b>：订单里的 userId 是对账与客诉追溯的线索，删了就断链。已取消、已拒单同样算，<br>
     *       因为它们仍是凭证。要收回权限请走「停用」。</li>
     *   <li><b>不把邀请码退回去</b>：{@code invite_use} 里那条明细保留，码的已用次数不减。<br>
     *       退回去等于让这张码凭空多出一个名额。</li>
     * </ul>
     *
     * <p>删地址不会误伤历史订单：订单里存的是下单那一刻的 {@code addressSnapshot} JSON 副本，<br>
     * 不引用 address 表。
     *
     * @return 连带删除的地址条数，仅用于日志与前端提示
     */
    @Transactional
    public int delete(Long operatorId, Long userId) {
        User user = requireCustomer(userId);

        Long orderCount = orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId));
        long orders = orderCount == null ? 0L : orderCount;
        if (orders > 0) {
            throw BizException.conflict("该用户有 " + orders + " 笔订单，只能停用，不能删除");
        }

        int addresses = addressMapper.delete(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId));
        userMapper.deleteById(userId);

        // 没有操作日志表，删除不可恢复，这条日志是唯一的留痕。
        // 不留手机号：日志会进文件与采集系统，删除动作本身没有「再联系这个人」的诉求
        log.info("删除用户 userId={} 昵称={} 状态={} 连带删地址={} 操作人={}",
                userId, user.getNickName(), user.getAuditStatus(), addresses, operatorId);

        // 用户行已经删了，操作记录里必须留下昵称快照 —— 否则事后只剩一串 UUID，
        // 谁也说不清当时删的是谁。手机号不留：删除后没有任何合法用途，
        // 而 audit_log 是长期留存的，留着就等于「删了却还在」
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_DELETE,
                "删除用户：" + displayName(user)
                        + "（" + label(user.getAuditStatus()) + "）｜账号 …" + idTail(userId)
                        + "｜连带删地址 " + addresses + " 条");
        return addresses;
    }

    /**
     * 指定为商家（需求 4.17）：把列表里已有的微信顾客就地升级为商家账号。
     *
     * <p>「就地」是关键决策（决策记录 D-26）：同一条 user 记录只改类型，<br>
     * openid / 昵称 / 订单 / 地址全部保留，不需要任何数据迁移 ——<br>
     * 升级后他再用微信打开小程序，{@code /auth/login} 按 openid 命中的就是 merchant 行，<br>
     * 小程序「我的」页宫格自然出现商家工作台入口。
     *
     * <p>审核状态一律置为 approved：商家账号不走群成员审核流程，
     * 之前停在 pending / rejected / disabled 都不构成障碍。本期不提供「取消商家」。
     *
     * @param username    后台登录账号，2 ~ 64 字符，全库唯一（uk_user_username）
     * @param rawPassword 明文密码，6 ~ 64 位，入库前 BCrypt 加密
     * @param roleIds     后台角色 ID 列表，至少一个；决定他能进后台做什么
     */
    @Transactional
    public void promote(Long operatorId, Long userId, String username,
                        String rawPassword, List<Long> roleIds) {
        User user = requireCustomer(userId);

        String account = username == null ? "" : username.trim();
        if (account.length() < 2 || account.length() > 64) {
            throw BizException.param("登录账号长度须在 2 ~ 64 字符之间");
        }
        if (rawPassword == null || rawPassword.length() < 6 || rawPassword.length() > 64) {
            throw BizException.param("登录密码长度须在 6 ~ 64 位之间");
        }

        // 全库查重：不只查 merchant。customer 行的 username 恒为 null，
        // 但万一将来出现占位，提前 409 也好过撞唯一索引
        Long exists = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, account));
        if (exists != null && exists > 0) {
            throw BizException.conflict("登录账号「" + account + "」已被占用，请换一个");
        }
        List<Long> roles = requireRoles(roleIds);

        String from = user.getAuditStatus();
        user.setUserType(UserType.MERCHANT);
        user.setUsername(account);
        user.setPasswordHash(passwordService.hash(rawPassword));
        user.setAuditStatus(AuditStatus.APPROVED);
        user.setRejectReason(null);
        user.setApprovedAt(LocalDateTime.now());
        user.setRoleIds(roles);
        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException ex) {
            // 并发窗口里的撞名：查重之后、提交之前又有人抢注了同一个账号
            throw BizException.conflict("登录账号「" + account + "」已被占用，请换一个");
        }
        log.info("指定为商家 userId={} username={} 角色={} 原状态={} 操作人={}",
                userId, account, roles, from, operatorId);

        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_PROMOTE,
                "指定为商家：" + display(user) + "｜后台账号：" + account
                        + "｜角色 " + roles.size() + " 个（" + label(from) + " → 商家）");
    }

    /**
     * 重置商家账号密码（v1.53 / 需求 4.17 / `F-J12`）。
     *
     * <p>为什么由后台代改而不是「发验证码让他自己改」：这套系统没有短信通道，
     * 商家忘了密码只能找店里有 `user.audit` 的人重置 —— 与其让他去翻数据库，不如给一个正经入口。
     *
     * <p><b>改密同时清锁定</b>：连续失败 3 次会锁 30 分钟（需求 4.1）。能改密码这件事本身就证明
     * 本人回来了，改完还锁着等于「改了也进不去」。
     *
     * <p>明文不落库、不回显、不进日志也不进操作记录 —— 记录里只写「改了谁的密码」。
     *
     * @param rawPassword 新密码，6 ~ 64 位
     */
    @Transactional
    public void resetMerchantPassword(Long operatorId, Long userId, String rawPassword) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("账号不存在");
        }
        if (!UserType.MERCHANT.equals(user.getUserType())) {
            throw BizException.param("只有商家账号有后台密码，群成员走微信登录");
        }
        if (rawPassword == null || rawPassword.length() < 6 || rawPassword.length() > 64) {
            throw BizException.param("登录密码长度须在 6 ~ 64 位之间");
        }

        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .set(User::getPasswordHash, passwordService.hash(rawPassword))
                .set(User::getLoginFailCount, 0)
                .set(User::getLockUntil, null)
                .eq(User::getId, userId));
        log.info("重置商家密码 userId={} username={} 操作人={}", userId, user.getUsername(), operatorId);

        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_PASSWORD,
                "重置商家密码：后台账号 " + (user.getUsername() == null ? "" : user.getUsername())
                        + "（改密同时解除登录锁定；密码内容不记录）");
    }

    /** 角色入参清洗：去重保序、逐个确认存在。至少一个，否则升级完进后台白屏。 */
    private List<Long> requireRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw BizException.param("请至少选择一个后台角色");
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            if (roleId != null) {
                distinct.add(roleId);
            }
        }
        if (distinct.isEmpty()) {
            throw BizException.param("请至少选择一个后台角色");
        }
        List<Role> found = roleMapper.selectBatchIds(List.copyOf(distinct));
        if (found.size() != distinct.size()) {
            throw BizException.param("所选角色不存在或已被删除，请刷新后重试");
        }
        return List.copyOf(distinct);
    }

    private User requireCustomer(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if (!UserType.CUSTOMER.equals(user.getUserType())) {
            throw BizException.param("只能操作群成员账号");
        }
        return user;
    }

    private static void requireStatus(String current, String actionName, String... allowed) {
        for (String status : allowed) {
            if (status.equals(current)) {
                return;
            }
        }
        throw BizException.conflict("当前状态（" + label(current) + "）不能执行「" + actionName + "」，请刷新后重试");
    }

    /**
     * 没填昵称就不能审核（通过 / 驳回都拦）。
     *
     * 审核列表里没昵称的用户只显示「未填昵称」，商家认不出是谁，通过和驳回都只能靠猜。
     * 所以把「填昵称」变成审核的前置条件：列表照常展示（否则用户不知道卡在哪），
     * 只是按钮不可用。停用 / 恢复不拦——那是管理动作，不是「认人」。
     */
    private static void requireNickName(User user) {
        if (user.getNickName() == null || user.getNickName().isBlank()) {
            throw BizException.conflict("该用户还没填昵称，暂时无法审核；请让对方先在「我的」页填写昵称");
        }
    }

    private static String label(String status) {
        return switch (status == null ? "" : status) {
            case AuditStatus.PENDING -> "待审核";
            case AuditStatus.APPROVED -> "已通过";
            case AuditStatus.REJECTED -> "已驳回";
            case AuditStatus.DISABLED -> "已停用";
            default -> status;
        };
    }

    private UserRow toRow(User user, boolean viaInvite) {
        String via = null;
        if (user.getGroupOpenId() != null && !user.getGroupOpenId().isBlank()) {
            via = "group";
        } else if (viaInvite) {
            via = "invite";
        }
        return new UserRow(user.getId(), user.getNickName(), user.getAvatarUrl(),
                MaskUtil.phone(user.getPhone()),
                user.getAuditStatus(), user.getRejectReason(), via,
                user.getCreatedAt(), user.getApprovedAt(), hasNickName(user));
    }

    private static boolean hasNickName(User user) {
        return user.getNickName() != null && !user.getNickName().isBlank();
    }

    /**
     * 列表行。不含 openid、passwordHash、roleIds：后台审核用不到，也不该出现在接口响应里。
     *
     * @param accessVia group / invite / null（未准入，正常不会出现在审核列表，除非商家手动建的号）
     * @param canAudit  是否能审核：没填昵称的用户列表照常展示，但不能通过 / 驳回
     */
    public record UserRow(Long userId, String nickName, String avatarUrl, String phone,
                          String auditStatus, String rejectReason, String accessVia,
                          LocalDateTime createdAt, LocalDateTime approvedAt, boolean canAudit) {
    }
}

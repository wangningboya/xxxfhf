package com.xxxfhf.meal.user;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.address.Address;
import com.xxxfhf.meal.address.AddressMapper;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.PhoneValidator;
import com.xxxfhf.meal.invite.InviteCode;
import com.xxxfhf.meal.invite.InviteCodeMapper;
import com.xxxfhf.meal.order.Order;
import com.xxxfhf.meal.order.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final InviteCodeMapper inviteCodeMapper;
    private final AddressMapper addressMapper;
    private final OrderMapper orderMapper;
    private final AuditService auditService;

    public UserAuditService(UserMapper userMapper, InviteCodeMapper inviteCodeMapper,
                            AddressMapper addressMapper, OrderMapper orderMapper,
                            AuditService auditService) {
        this.userMapper = userMapper;
        this.inviteCodeMapper = inviteCodeMapper;
        this.addressMapper = addressMapper;
        this.orderMapper = orderMapper;
        this.auditService = auditService;
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

        Set<String> inviteUsers = inviteUserIds(result.getRecords());
        List<UserRow> rows = new ArrayList<>(result.getRecords().size());
        for (User user : result.getRecords()) {
            rows.add(toRow(user, inviteUsers.contains(user.getId())));
        }

        Page<UserRow> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(rows);
        return mapped;
    }

    /**
     * 批量查本页用户里谁用过邀请码，避免逐行查 invite_code（N+1）。
     *
     * 这里用字符串列名而不是 Lambda：本方法在单元测试里也会被触达，
     * Lambda 需要 MyBatis-Plus 的 TableInfo 已初始化，纯单测环境拿不到。
     */
    private Set<String> inviteUserIds(List<User> users) {
        List<String> ids = users.stream().map(User::getId).toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<InviteCode> codes = inviteCodeMapper.selectList(Wrappers.<InviteCode>query()
                .select("used_by")
                .in("used_by", ids));
        Set<String> set = new HashSet<>(codes.size());
        for (InviteCode code : codes) {
            if (code.getUsedBy() != null) {
                set.add(code.getUsedBy());
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
    public UserRow audit(String operatorId, String userId, String action, String reason) {
        User user = requireCustomer(userId);

        String from = user.getAuditStatus();
        String to = switch (action == null ? "" : action) {
            case AuditAction.APPROVE -> {
                requireStatus(from, "审核通过", AuditStatus.PENDING, AuditStatus.REJECTED);
                user.setApprovedAt(LocalDateTime.now());
                user.setRejectReason(null);
                yield AuditStatus.APPROVED;
            }
            case AuditAction.REJECT -> {
                requireStatus(from, "驳回", AuditStatus.PENDING, AuditStatus.REJECTED);
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

        Set<String> inviteUsers = inviteUserIds(List.of(user));
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

    /** 操作记录里指代一个用户：昵称 + 手机号，够认出是谁 */
    private static String display(User user) {
        String name = user.getNickName() == null || user.getNickName().isBlank()
                ? user.getId() : user.getNickName();
        return user.getPhone() == null || user.getPhone().isBlank()
                ? name : name + " " + user.getPhone();
    }

    /** 商家代填 / 修改用户手机号（用户没填或填错时用）。 */
    @Transactional
    public UserRow updatePhone(String operatorId, String userId, String phone) {
        User user = requireCustomer(userId);
        String normalized = PhoneValidator.require(phone);
        String before = user.getPhone();
        user.setPhone(normalized);
        userMapper.updateById(user);
        log.info("商家代填手机号 userId={} 操作人={}", userId, operatorId);
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_PHONE,
                "商家修改手机号：" + display(user) + "｜"
                        + (before == null || before.isBlank() ? "原为空" : before) + " → " + normalized);
        Set<String> inviteUsers = inviteUserIds(List.of(user));
        return toRow(user, inviteUsers.contains(user.getId()));
    }

    /**
     * 物理删除用户，并连带删掉他名下的收货地址（需求 4.17「删除用户」）。
     *
     * <p>两条硬约束：
     * <ul>
     *   <li><b>有订单就拒删</b>：订单里的 userId 是对账与客诉追溯的线索，删了就断链。已取消、已拒单同样算，<br>
     *       因为它们仍是凭证。要收回权限请走「停用」。</li>
     *   <li><b>不把邀请码退回去</b>：{@code invite_code.used_by} 保留原值不置空。置空会让这个码<br>
     *       变回「未使用」而能被第二个人再用一遍。</li>
     * </ul>
     *
     * <p>删地址不会误伤历史订单：订单里存的是下单那一刻的 {@code addressSnapshot} JSON 副本，<br>
     * 不引用 address 表。
     *
     * @return 连带删除的地址条数，仅用于日志与前端提示
     */
    @Transactional
    public int delete(String operatorId, String userId) {
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

        // 没有操作日志表，删除不可恢复，这条日志是唯一的留痕
        log.info("删除用户 userId={} 昵称={} 手机号={} 状态={} 连带删地址={} 操作人={}",
                userId, user.getNickName(), user.getPhone(), user.getAuditStatus(), addresses, operatorId);

        // 用户行已经删了，操作记录里必须留下昵称与手机号快照 —— 否则事后只剩一串 UUID，
        // 谁也说不清当时删的是谁
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_DELETE,
                "删除用户：" + display(user)
                        + "（" + label(user.getAuditStatus()) + "）｜连带删地址 " + addresses + " 条");
        return addresses;
    }

    private User requireCustomer(String userId) {
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
        return new UserRow(user.getId(), user.getNickName(), user.getAvatarUrl(), user.getPhone(),
                user.getAuditStatus(), user.getRejectReason(), via,
                user.getCreatedAt(), user.getApprovedAt());
    }

    /**
     * 列表行。不含 openid、passwordHash、roleIds：后台审核用不到，也不该出现在接口响应里。
     *
     * @param accessVia group / invite / null（未准入，正常不会出现在审核列表，除非商家手动建的号）
     */
    public record UserRow(String userId, String nickName, String avatarUrl, String phone,
                          String auditStatus, String rejectReason, String accessVia,
                          LocalDateTime createdAt, LocalDateTime approvedAt) {
    }
}

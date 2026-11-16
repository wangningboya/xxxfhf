package com.xxxfhf.meal.user;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.PhoneValidator;
import com.xxxfhf.meal.invite.InviteCode;
import com.xxxfhf.meal.invite.InviteCodeMapper;
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

    public UserAuditService(UserMapper userMapper, InviteCodeMapper inviteCodeMapper) {
        this.userMapper = userMapper;
        this.inviteCodeMapper = inviteCodeMapper;
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

        Set<String> inviteUsers = inviteUserIds(List.of(user));
        return toRow(user, inviteUsers.contains(user.getId()));
    }

    /** 商家代填 / 修改用户手机号（用户没填或填错时用）。 */
    @Transactional
    public UserRow updatePhone(String operatorId, String userId, String phone) {
        User user = requireCustomer(userId);
        String normalized = PhoneValidator.require(phone);
        user.setPhone(normalized);
        userMapper.updateById(user);
        log.info("商家代填手机号 userId={} 操作人={}", userId, operatorId);
        Set<String> inviteUsers = inviteUserIds(List.of(user));
        return toRow(user, inviteUsers.contains(user.getId()));
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

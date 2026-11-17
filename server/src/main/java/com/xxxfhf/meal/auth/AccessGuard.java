package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.springframework.stereotype.Component;

/**
 * 准入与审核的守门人。**准入与审核是两件事，分别判定**（技术文档 4.9）：
 *
 * - 准入：`group_open_id` 非空，或 invite_code.used_by 命中该用户 → 能进小程序、能看菜单
 * - 审核：`audit_status` → 能下单
 *
 * 所以「已准入但待审核」的用户看得到菜单、下不了单，这是需求里明确要的行为。
 *
 * 每次判定都从库里读，不缓存 —— 商家一停用，用户下一个请求就该被拦住。
 */
@Component
public class AccessGuard {

    private final UserMapper userMapper;
    private final GroupAccessService groupAccessService;

    public AccessGuard(UserMapper userMapper, GroupAccessService groupAccessService) {
        this.userMapper = userMapper;
        this.groupAccessService = groupAccessService;
    }

    /** 已准入即可：浏览菜单、看自己的审核状态用这个。 */
    public User requireAccess(String userId) {
        User user = requireUser(userId);
        GroupAccessService.AccessState state = groupAccessService.resolve(user);
        if (!state.accessGranted()) {
            throw BizException.forbidden("还没有获得本店准入资格，请从群卡片进入或使用邀请码");
        }
        return user;
    }

    /** 已准入 **且** 审核通过：下单、取消、声称已付款这类会改变业务数据的操作用这个。 */
    public User requireApproved(String userId) {
        return requireApproved(requireAccess(userId));
    }

    /** 手上已经有 User 对象时用这个重载，省一次查询。 */
    public User requireApproved(User user) {
        if (!AuditStatus.APPROVED.equals(user.getAuditStatus())) {
            throw BizException.forbidden(switch (user.getAuditStatus() == null ? "" : user.getAuditStatus()) {
                case AuditStatus.PENDING -> "账号还在审核中，商家通过后才能下单";
                case AuditStatus.REJECTED -> "账号申请已被驳回"
                        + (user.getRejectReason() == null || user.getRejectReason().isBlank()
                        ? "" : "：" + user.getRejectReason());
                case AuditStatus.DISABLED -> "账号已停用，请联系商家";
                default -> "账号状态异常，请联系商家";
            });
        }
        return user;
    }

    private User requireUser(String userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("登录信息已失效，请重新登录");
        }
        return user;
    }
}

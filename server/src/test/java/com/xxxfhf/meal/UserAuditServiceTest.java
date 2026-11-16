package com.xxxfhf.meal;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.invite.InviteCodeMapper;
import com.xxxfhf.meal.user.AuditAction;
import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserAuditService;
import com.xxxfhf.meal.user.UserMapper;
import com.xxxfhf.meal.user.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核状态机。需求里的四条流转规则在这里逐条钉死：
 * approve（pending/rejected → approved）、reject（必填原因）、
 * disable（任意 → disabled）、enable（仅 disabled → approved）。
 */
@ExtendWith(MockitoExtension.class)
class UserAuditServiceTest {

    private static final String OPERATOR = "u_merchant_1";
    private static final String TARGET = "u_customer_1";

    @Mock
    private UserMapper userMapper;

    @Mock
    private InviteCodeMapper inviteCodeMapper;

    @InjectMocks
    private UserAuditService service;

    @Test
    @DisplayName("待审核 → 通过：写入 approvedAt")
    void approveFromPending() {
        User user = customer(AuditStatus.PENDING);
        when(userMapper.selectById(TARGET)).thenReturn(user);

        UserAuditService.UserRow row = service.audit(OPERATOR, TARGET, AuditAction.APPROVE, null);

        assertThat(row.auditStatus()).isEqualTo(AuditStatus.APPROVED);
        assertThat(user.getApprovedAt()).as("通过时间必须落库").isNotNull();
        assertThat(user.getRejectReason()).as("通过后不应残留驳回原因").isNull();
        verify(userMapper).updateById(user);
    }

    @Test
    @DisplayName("已驳回 → 通过：允许商家改判")
    void approveFromRejected() {
        User user = customer(AuditStatus.REJECTED);
        user.setRejectReason("不是本群成员");
        when(userMapper.selectById(TARGET)).thenReturn(user);

        UserAuditService.UserRow row = service.audit(OPERATOR, TARGET, AuditAction.APPROVE, null);

        assertThat(row.auditStatus()).isEqualTo(AuditStatus.APPROVED);
        assertThat(user.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("驳回不填原因直接拒绝")
    void rejectRequiresReason() {
        User user = customer(AuditStatus.PENDING);
        when(userMapper.selectById(TARGET)).thenReturn(user);

        assertThatThrownBy(() -> service.audit(OPERATOR, TARGET, AuditAction.REJECT, "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驳回必须填写原因");

        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("已通过的单不能再驳回，要收回权限请用停用")
    void cannotRejectApproved() {
        User user = customer(AuditStatus.APPROVED);
        when(userMapper.selectById(TARGET)).thenReturn(user);

        assertThatThrownBy(() -> service.audit(OPERATOR, TARGET, AuditAction.REJECT, "改主意了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已通过");

        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("已通过 → 停用")
    void disableApproved() {
        User user = customer(AuditStatus.APPROVED);
        when(userMapper.selectById(TARGET)).thenReturn(user);

        UserAuditService.UserRow row = service.audit(OPERATOR, TARGET, AuditAction.DISABLE, null);

        assertThat(row.auditStatus()).isEqualTo(AuditStatus.DISABLED);
    }

    @Test
    @DisplayName("待审核不能「恢复」，恢复只对已停用有效")
    void cannotEnablePending() {
        User user = customer(AuditStatus.PENDING);
        when(userMapper.selectById(TARGET)).thenReturn(user);

        assertThatThrownBy(() -> service.audit(OPERATOR, TARGET, AuditAction.ENABLE, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("待审核");
    }

    @Test
    @DisplayName("停用 → 恢复为已通过")
    void enableDisabled() {
        User user = customer(AuditStatus.DISABLED);
        when(userMapper.selectById(TARGET)).thenReturn(user);

        UserAuditService.UserRow row = service.audit(OPERATOR, TARGET, AuditAction.ENABLE, null);

        assertThat(row.auditStatus()).isEqualTo(AuditStatus.APPROVED);
    }

    @Test
    @DisplayName("不能审核商家账号")
    void cannotAuditMerchant() {
        User merchant = new User();
        merchant.setId(TARGET);
        merchant.setUserType(UserType.MERCHANT);
        merchant.setAuditStatus(AuditStatus.APPROVED);
        when(userMapper.selectById(TARGET)).thenReturn(merchant);

        assertThatThrownBy(() -> service.audit(OPERATOR, TARGET, AuditAction.DISABLE, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能操作群成员");
    }

    @Test
    @DisplayName("未知动作报参数错误，不改库")
    void unknownActionRejected() {
        User user = customer(AuditStatus.PENDING);
        when(userMapper.selectById(TARGET)).thenReturn(user);

        assertThatThrownBy(() -> service.audit(OPERATOR, TARGET, "activate", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的审核动作");

        verify(userMapper, never()).updateById(any());
    }

    private static User customer(String auditStatus) {
        User user = new User();
        user.setId(TARGET);
        user.setUserType(UserType.CUSTOMER);
        user.setAuditStatus(auditStatus);
        return user;
    }
}

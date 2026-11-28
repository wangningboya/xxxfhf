package com.xxxfhf.meal.invite;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.config.AppProperties;
import com.xxxfhf.meal.invite.InviteAttemptGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邀请码错误次数限制：30 分钟内错 10 次锁 30 分钟。
 *
 * <p>锁定期用真实时间算，这里只验证「第 10 次失败开始拒绝」与「成功一次清零」，
 * 不为了测过期去改系统时间。
 */
class InviteAttemptGuardTest {

    /** 阈值与时长现在来自 AppProperties 默认值（10 次 / 30 分钟），与改前一致 */
    private final InviteAttemptGuard guard = new InviteAttemptGuard(new AppProperties());

    @Test
    @DisplayName("没失败过：直接放行")
    void noFailurePasses() {
        assertThatCode(() -> guard.check("openid-a")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("错 9 次还能试，第 10 次开始锁")
    void locksAfterTenFailures() {
        for (int i = 0; i < 9; i++) {
            guard.onFailure("openid-a");
        }
        // 第 9 次之前都没锁
        assertThatCode(() -> guard.check("openid-a")).doesNotThrowAnyException();

        guard.onFailure("openid-a");

        assertThatThrownBy(() -> guard.check("openid-a"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("错误次数过多")
                .hasMessageContaining("分钟");
    }

    @Test
    @DisplayName("别的账号不受影响")
    void otherAccountsUnaffected() {
        for (int i = 0; i < 10; i++) {
            guard.onFailure("openid-a");
        }

        assertThatCode(() -> guard.check("openid-b")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("核销成功清零：手滑错几次不该一直背着")
    void successResets() {
        for (int i = 0; i < 9; i++) {
            guard.onFailure("openid-a");
        }
        guard.onSuccess("openid-a");

        for (int i = 0; i < 9; i++) {
            guard.onFailure("openid-a");
        }
        // 清零后又错 9 次，仍未到 10 次，不应锁
        assertThatCode(() -> guard.check("openid-a")).doesNotThrowAnyException();
    }
}

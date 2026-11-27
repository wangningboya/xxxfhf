package com.xxxfhf.meal;

import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.auth.SessionKeyStore;
import com.xxxfhf.meal.auth.WxCrypto;
import com.xxxfhf.meal.auth.service.GroupAccessService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ContentSecurityService;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.invite.InviteAttemptGuard;
import com.xxxfhf.meal.invite.service.InviteCodeService;
import com.xxxfhf.meal.user.domain.AuditStatus;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 邀请码爆破的防线：同一个微信号 30 分钟内错 10 次就锁，锁住后不再碰数据库。
 *
 * <p>这里测的是 {@link GroupAccessService#bindByInvite} 与 {@link InviteAttemptGuard} 的配合，
 * 所以 guard 用真实实例——它没有依赖、纯进程内计数，换成 mock 就等于把要测的东西删掉了。
 * guard 自身的计数规则另见 {@code InviteAttemptGuardTest}；单 IP 的高频并发见
 * {@code RateLimitFilterTest#concurrentBruteForceOnJoinIsCapped}。两道防线各管一个维度：
 * 限流管「打太快」，本类管「猜太多」。
 *
 * <p>代价说明：计数在进程内，服务重启即清零；换一个微信号也是全新的一份额度。
 * 后者是已知缺口（见《邀请码防爆破方案.md》），最后一条用例把这个边界固定下来，
 * 将来改额度阈值或计数维度时它会亮。
 */
@ExtendWith(MockitoExtension.class)
class InviteBruteForceTest {

    private static final String OPENID = "openid-brute-force-0001";

    @Mock
    private UserMapper userMapper;

    @Mock
    private SessionKeyStore sessionKeyStore;

    @Mock
    private WxCrypto wxCrypto;

    @Mock
    private ConfigService configService;

    @Mock
    private InviteCodeService inviteCodeService;

    @Mock
    private AuditService auditService;

    @Mock
    private ContentSecurityService contentSecurity;

    private GroupAccessService service;

    @BeforeEach
    void setUp() {
        // 真实 guard：每个用例一份干净计数
        service = new GroupAccessService(userMapper, sessionKeyStore, wxCrypto, configService,
                inviteCodeService, new InviteAttemptGuard(), auditService, contentSecurity);
    }

    @Test
    @DisplayName("错满 10 次后锁定：第 11 次连核销的 SQL 都不产生")
    void eleventhAttemptNeverReachesDatabase() {
        // 每次核销都被业务拒绝（码不对），这是爆破时的真实形态
        doThrow(BizException.notFound("邀请码不存在"))
                .when(inviteCodeService).consume(anyString(), anyLong());

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.bindByInvite(pendingUser(OPENID), "000000"))
                    .isInstanceOf(BizException.class);
        }

        // 第 11 次：被 guard 挡在 consume 之前，既不查库也不消耗商家的码
        assertThatThrownBy(() -> service.bindByInvite(pendingUser(OPENID), "000000"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("错误次数过多")
                .hasMessageContaining("分钟");

        verify(inviteCodeService, times(10)).consume(anyString(), anyLong());
    }

    @Test
    @DisplayName("锁的是微信号不是码：换一张码继续猜照样被拒")
    void lockFollowsOpenidNotCode() {
        doThrow(BizException.notFound("邀请码不存在"))
                .when(inviteCodeService).consume(anyString(), anyLong());

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.bindByInvite(pendingUser(OPENID), "111111"))
                    .isInstanceOf(BizException.class);
        }

        // 攻击者看到「这个码不对」后通常会换下一个数字——这里必须仍然锁着
        assertThatThrownBy(() -> service.bindByInvite(pendingUser(OPENID), "222222"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("错误次数过多");

        verify(inviteCodeService, times(10)).consume(anyString(), anyLong());
    }

    @Test
    @DisplayName("已知缺口：换一个微信号就是全新的一份 10 次额度（本层拦不住，靠限流与码空间）")
    void anotherWechatAccountStartsFromZero() {
        doThrow(BizException.notFound("邀请码不存在"))
                .when(inviteCodeService).consume(anyString(), anyLong());

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.bindByInvite(pendingUser(OPENID), "000000"))
                    .isInstanceOf(BizException.class);
        }

        // 换号后第一次仍然放行——这正是「20 个号 = 20 份额度」的来源。
        // 把额度调低（例如 MAX_FAILURES 10→5）时这条会亮：那时第 6 次就锁了。
        assertThatThrownBy(() -> service.bindByInvite(pendingUser("openid-another"), "000000"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邀请码不存在");

        verify(inviteCodeService, times(11)).consume(anyString(), anyLong());
    }

    @Test
    @DisplayName("数据库异常不计数：一次故障不该把正常用户锁在门外")
    void systemFailureDoesNotCountAsWrongCode() {
        // 不是用户的错：连接池打满、主从切换，抛的是 RuntimeException 而不是 BizException
        doThrow(new IllegalStateException("connection reset"))
                .when(inviteCodeService).consume(anyString(), anyLong());

        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> service.bindByInvite(pendingUser(OPENID), "000000"))
                    .isInstanceOf(IllegalStateException.class);
        }

        // 12 次系统异常之后仍然没被锁：换回正常的业务拒绝，提示应该还是「码不对」
        doThrow(BizException.notFound("邀请码不存在"))
                .when(inviteCodeService).consume(anyString(), anyLong());

        assertThatThrownBy(() -> service.bindByInvite(pendingUser(OPENID), "000000"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邀请码不存在");
    }

    /** 待审核的新用户：既没被禁用也没通过，是能走到核销的唯一状态 */
    private static User pendingUser(String openid) {
        User user = new User();
        user.setId(1L);
        user.setOpenid(openid);
        user.setAuditStatus(AuditStatus.PENDING);
        return user;
    }
}

package com.xxxfhf.meal.auth.service;

import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.auth.SessionKeyStore;
import com.xxxfhf.meal.auth.WxApiClient;
import com.xxxfhf.meal.auth.WxCrypto;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ContentSecurityService;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.invite.InviteAttemptGuard;
import com.xxxfhf.meal.invite.service.InviteCodeService;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 昵称保存链路：微信判定 → 拦不拦 → 落不落库（技术文档 4.18）。
 *
 * <p>只把最底层的 {@link WxApiClient} 换成 mock，中间用**真的** {@link ContentSecurityService}——
 * 把中间层也 mock 掉的话，这次「判了但没拦住」的缺陷照样测不出来。
 *
 * <p>与另外两个测试的分工：{@code WxApiClientSecCheckTest} 管「微信返回体 → 错误码」，
 * {@code ContentSecurityServiceTest} 管「错误码 → 放行/拒绝」，本类管「拒绝之后到底有没有挡住入库」。
 */
@ExtendWith(MockitoExtension.class)
class GroupAccessServiceNickNameTest {

    private static final Long USER_ID = 1L;
    private static final String OPENID = "openid-1";

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
    private InviteAttemptGuard inviteAttemptGuard;
    @Mock
    private AuditService auditService;
    @Mock
    private WxApiClient wxApiClient;

    private GroupAccessService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new GroupAccessService(userMapper, sessionKeyStore, wxCrypto, configService,
                inviteCodeService, inviteAttemptGuard, auditService,
                new ContentSecurityService(wxApiClient));
        user = new User();
        user.setId(USER_ID);
        user.setOpenid(OPENID);
    }

    @Test
    void riskyNickNameIsRejectedAndNotSaved() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        // WxApiClient 对 2.0 的 risky 结论返回的就是这个错误码（见 judgeSecCheck）
        when(wxApiClient.msgSecCheck("违规昵称", OPENID)).thenReturn(WxApiClient.CONTENT_RISKY);

        assertThatThrownBy(() -> service.updateNickName(USER_ID, "违规昵称"))
                .isInstanceOf(BizException.class)
                .hasMessage("昵称包含违规内容，请修改");

        verify(userMapper, never()).updateById(any(User.class));
        assertThat(user.getNickName()).isNull();
    }

    @Test
    void cleanNickNameIsCleanedAndSaved() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(wxApiClient.msgSecCheck("小 明", OPENID)).thenReturn(0);

        // 清洗规则：连续空白压成一个半角空格、首尾裁掉（NickNameValidator）
        String saved = service.updateNickName(USER_ID, "  小  明  ");

        assertThat(saved).isEqualTo("小 明");
        assertThat(user.getNickName()).isEqualTo("小 明");
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    void wxUnavailableIsBestEffortAndStillSaves() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        // -1 = 本地调用失败（网络、凭证、access_token 取不到）：按「异常放行」处理
        when(wxApiClient.msgSecCheck("小明", OPENID)).thenReturn(-1);

        assertThat(service.updateNickName(USER_ID, "小明")).isEqualTo("小明");

        verify(userMapper).updateById(any(User.class));
    }
}

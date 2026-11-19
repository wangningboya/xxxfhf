package com.xxxfhf.meal.common;

import com.xxxfhf.meal.auth.WxApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容安全策略：只有 87014 拒绝，其他一律放行（P0-12）。
 *
 * <p>这里 mock 掉 {@link WxApiClient}，验的是「错误码 → 放行/拒绝」的判定，
 * 不验真实调用。真机那步（真实违规词、断网）必须人工走一遍。
 */
@ExtendWith(MockitoExtension.class)
class ContentSecurityServiceTest {

    @Mock
    private WxApiClient wxApiClient;

    @InjectMocks
    private ContentSecurityService contentSecurityService;

    @Test
    void blankContentPassesWithoutCallingWx() {
        assertThat(contentSecurityService.pass(1L, "openid", "   ")).isTrue();
        verify(wxApiClient, never()).msgSecCheck(any(), any());
    }

    @Test
    void riskyContentIsRejected() {
        when(wxApiClient.msgSecCheck("违规文本", "openid")).thenReturn(WxApiClient.CONTENT_RISKY);

        assertThat(contentSecurityService.pass(1L, "openid", "违规文本")).isFalse();
    }

    @Test
    void localFailurePasses() {
        // -1 = 本地调用失败（网络、没配 appSecret），不能当成违规拒绝
        when(wxApiClient.msgSecCheck("正常昵称", "openid")).thenReturn(-1);

        assertThat(contentSecurityService.pass(1L, "openid", "正常昵称")).isTrue();
    }

    @Test
    void unknownErrcodePasses() {
        when(wxApiClient.msgSecCheck("正常昵称", "openid")).thenReturn(45009);

        assertThat(contentSecurityService.pass(1L, "openid", "正常昵称")).isTrue();
    }

    @Test
    void twoArgOverloadDelegatesWithoutUserId() {
        when(wxApiClient.msgSecCheck("正常昵称", "openid")).thenReturn(0);

        assertThat(contentSecurityService.pass("openid", "正常昵称")).isTrue();
    }
}

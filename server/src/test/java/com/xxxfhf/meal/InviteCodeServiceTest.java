package com.xxxfhf.meal;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.invite.InviteCode;
import com.xxxfhf.meal.invite.InviteCodeMapper;
import com.xxxfhf.meal.invite.InviteCodeService;
import com.xxxfhf.meal.invite.InviteStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邀请码生成与核销。
 *
 * 核销的判定权在那条带条件的 UPDATE 上，所以这里重点测「affectedRows = 0 时给出什么提示」，
 * 而不是测「先查再改」——后者在并发下会让一个码被两个人用掉。
 */
@ExtendWith(MockitoExtension.class)
class InviteCodeServiceTest {

    private static final String USER = "u_customer_1";

    @Mock
    private InviteCodeMapper inviteCodeMapper;

    @InjectMocks
    private InviteCodeService service;

    @Test
    @DisplayName("生成的码是 8 位，且不含易混字符 0/O/1/I/L")
    void generatedCodeIsReadable() {
        InviteCode code = service.create(24, "u_merchant_1");

        assertThat(code.getCode()).hasSize(8);
        assertThat(code.getCode()).matches("^[A-HJ-KM-NP-Z2-9]{8}$");
        assertThat(code.getStatus()).isEqualTo(InviteStatus.UNUSED);
        assertThat(code.getExpireAt()).isAfter(LocalDateTime.now().plusHours(23));
        verify(inviteCodeMapper).insert(any(InviteCode.class));
    }

    @Test
    @DisplayName("不传有效期按 24 小时")
    void defaultExpireIs24Hours() {
        InviteCode code = service.create(0, "u_merchant_1");

        assertThat(code.getExpireAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(code.getExpireAt()).isBefore(LocalDateTime.now().plusHours(25));
    }

    @Test
    @DisplayName("有效期超过 30 天直接拒绝")
    void expireTooLongRejected() {
        assertThatThrownBy(() -> service.create(721, "u_merchant_1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("30 天");
        verify(inviteCodeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("核销成功不查库（一条 UPDATE 就够了）")
    void consumeSucceeds() {
        when(inviteCodeMapper.consume("ABCD2345", USER)).thenReturn(1);

        service.consume("abcd2345", USER);

        // 入参大小写不应影响结果：用户手输常是小写
        verify(inviteCodeMapper).consume("ABCD2345", USER);
    }

    @Test
    @DisplayName("码不存在：提示不存在")
    void consumeNotFound() {
        when(inviteCodeMapper.consume(anyString(), eq(USER))).thenReturn(0);
        when(inviteCodeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.consume("ZZZZ9999", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("码已被用掉：提示已被使用，不能重复用")
    void consumeAlreadyUsed() {
        when(inviteCodeMapper.consume(anyString(), eq(USER))).thenReturn(0);
        InviteCode used = new InviteCode();
        used.setCode("ABCD2345");
        used.setStatus(InviteStatus.USED);
        when(inviteCodeMapper.selectOne(any())).thenReturn(used);

        assertThatThrownBy(() -> service.consume("ABCD2345", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被使用");
    }

    @Test
    @DisplayName("码已过期：提示已过期")
    void consumeExpired() {
        when(inviteCodeMapper.consume(anyString(), eq(USER))).thenReturn(0);
        InviteCode expired = new InviteCode();
        expired.setCode("ABCD2345");
        expired.setStatus(InviteStatus.UNUSED);
        expired.setExpireAt(LocalDateTime.now().minusHours(1));
        when(inviteCodeMapper.selectOne(any())).thenReturn(expired);

        assertThatThrownBy(() -> service.consume("ABCD2345", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    @DisplayName("空码不发 SQL")
    void blankCodeRejected() {
        assertThatThrownBy(() -> service.consume("   ", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请输入邀请码");
        verify(inviteCodeMapper, never()).consume(anyString(), anyString());
    }
}

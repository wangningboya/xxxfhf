package com.xxxfhf.meal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.invite.InviteCode;
import com.xxxfhf.meal.invite.InviteCodeMapper;
import com.xxxfhf.meal.invite.InviteCodeService;
import com.xxxfhf.meal.invite.InviteStatus;
import com.xxxfhf.meal.invite.InviteUse;
import com.xxxfhf.meal.invite.InviteUseMapper;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 邀请码生成与核销。
 *
 * 核销的判定权在那条带条件的 UPDATE 上，所以这里重点测「affectedRows = 0 时给出什么提示」，
 * 以及「扣次数与写明细必须一起发生」，而不是测「先查再改」——后者在并发下会超发。
 */
@ExtendWith(MockitoExtension.class)
class InviteCodeServiceTest {

    private static final Long USER = 1L;

    @Mock
    private InviteCodeMapper inviteCodeMapper;

    @Mock
    private InviteUseMapper inviteUseMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private InviteCodeService service;

    /**
     * 单测不起 Spring 容器，MyBatis-Plus 的 lambda 列名缓存不会自动建立；
     * 作废用例里的 lambdaUpdate().set(...) 会立即解析列名，必须先建缓存。
     */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, InviteCode.class);
        TableInfoHelper.initTableInfo(assistant, InviteUse.class);
    }

    @Test
    @DisplayName("生成的码是 6 位数字，次数落到 maxUses")
    void generatedCodeIsSixDigits() {
        InviteCode code = service.create(20, 24, 100L);

        assertThat(code.getCode()).hasSize(6);
        assertThat(code.getCode()).matches("^\\d{6}$");
        assertThat(code.getStatus()).isEqualTo(InviteStatus.UNUSED);
        assertThat(code.getMaxUses()).isEqualTo(20);
        assertThat(code.getUsedCount()).isZero();
        assertThat(code.getExpireAt()).isAfter(LocalDateTime.now().plusHours(23));
        verify(inviteCodeMapper).insert(any(InviteCode.class));
        // 发码等于放人进来，必须留一条 invite.create，且写明次数
        verify(auditService).record(eq(AuditActions.TABLE_INVITE), any(), eq(AuditActions.INVITE_CREATE),
                contains("20 次"));
    }

    @Test
    @DisplayName("不传有效期按 24 小时")
    void defaultExpireIs24Hours() {
        InviteCode code = service.create(1, 0, 100L);

        assertThat(code.getExpireAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(code.getExpireAt()).isBefore(LocalDateTime.now().plusHours(25));
    }

    @Test
    @DisplayName("有效期超过 30 天直接拒绝")
    void expireTooLongRejected() {
        assertThatThrownBy(() -> service.create(1, 721, 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("30 天");
        verify(inviteCodeMapper, never()).insert(any(InviteCode.class));
    }

    @Test
    @DisplayName("次数小于 1 直接拒绝，不静默改成 1 次")
    void usesMustBePositive() {
        assertThatThrownBy(() -> service.create(0, 24, 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少 1 次");
        verify(inviteCodeMapper, never()).insert(any(InviteCode.class));
    }

    @Test
    @DisplayName("撞码就换一个重试：第一次重复，第二次成功")
    void duplicateCodeRetries() {
        when(inviteCodeMapper.insert(any(InviteCode.class)))
                .thenThrow(new DuplicateKeyException("uk_invite_code"))
                .thenReturn(1);

        InviteCode code = service.create(5, 24, 100L);

        assertThat(code.getCode()).matches("^\\d{6}$");
        verify(inviteCodeMapper, times(2)).insert(any(InviteCode.class));
        // 撞码的那次不留审计，只有真正插进去的那次记一条
        verify(auditService, times(1)).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("连续撞满 20 次：报「号段接近用尽」，不死循环")
    void duplicateCodeGivesUpAfterRetries() {
        doThrow(new DuplicateKeyException("uk_invite_code"))
                .when(inviteCodeMapper).insert(any(InviteCode.class));

        assertThatThrownBy(() -> service.create(5, 24, 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("号段");
        verify(inviteCodeMapper, times(20)).insert(any(InviteCode.class));
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("核销成功：扣次数 + 写一条使用明细，两者在同一笔里")
    void consumeSucceedsAndWritesUse() {
        InviteCode row = new InviteCode();
        row.setId(9L);
        row.setCode("012345");
        row.setMaxUses(20);
        row.setUsedCount(1);
        when(inviteCodeMapper.consume("012345")).thenReturn(1);
        when(inviteCodeMapper.selectOne(any())).thenReturn(row);

        service.consume("012345", USER);

        verify(inviteCodeMapper).consume("012345");
        verify(inviteUseMapper).insert(argThat((InviteUse use) ->
                use.getInviteCodeId().equals(9L) && use.getUserId().equals(USER) && use.getUsedAt() != null));
    }

    @Test
    @DisplayName("前后空格不影响核销（用户常从聊天里复制）")
    void consumeTrimsInput() {
        InviteCode row = new InviteCode();
        row.setId(9L);
        row.setMaxUses(1);
        row.setUsedCount(1);
        when(inviteCodeMapper.consume("012345")).thenReturn(1);
        when(inviteCodeMapper.selectOne(any())).thenReturn(row);

        service.consume(" 012345 ", USER);

        verify(inviteCodeMapper).consume("012345");
    }

    @Test
    @DisplayName("码不存在：提示不存在")
    void consumeNotFound() {
        when(inviteCodeMapper.consume(anyString())).thenReturn(0);
        when(inviteCodeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.consume("999999", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("次数已用完：提示用完，不再提「已被使用」")
    void consumeUsedUp() {
        when(inviteCodeMapper.consume(anyString())).thenReturn(0);
        InviteCode used = new InviteCode();
        used.setCode("012345");
        used.setStatus(InviteStatus.USED);
        used.setMaxUses(20);
        used.setUsedCount(20);
        when(inviteCodeMapper.selectOne(any())).thenReturn(used);

        assertThatThrownBy(() -> service.consume("012345", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("次数已用完");
    }

    @Test
    @DisplayName("码已过期：提示已过期")
    void consumeExpired() {
        when(inviteCodeMapper.consume(anyString())).thenReturn(0);
        InviteCode expired = new InviteCode();
        expired.setCode("012345");
        expired.setStatus(InviteStatus.PARTIAL);
        expired.setMaxUses(20);
        expired.setUsedCount(3);
        expired.setExpireAt(LocalDateTime.now().minusHours(1));
        when(inviteCodeMapper.selectOne(any())).thenReturn(expired);

        assertThatThrownBy(() -> service.consume("012345", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    @DisplayName("码已作废：提示找商家重新要")
    void consumeRevoked() {
        when(inviteCodeMapper.consume(anyString())).thenReturn(0);
        InviteCode revoked = new InviteCode();
        revoked.setCode("012345");
        revoked.setStatus(InviteStatus.REVOKED);
        revoked.setMaxUses(20);
        revoked.setUsedCount(0);
        when(inviteCodeMapper.selectOne(any())).thenReturn(revoked);

        assertThatThrownBy(() -> service.consume("012345", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("作废");
    }

    @Test
    @DisplayName("空码不发 SQL")
    void blankCodeRejected() {
        assertThatThrownBy(() -> service.consume("   ", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请输入邀请码");
        verify(inviteCodeMapper, never()).consume(anyString());
    }

    @Test
    @DisplayName("使用者名单：码 id 为空不查库")
    void usesOfSkipsQueryWhenNoCode() {
        assertThat(service.usesOf(null)).isEmpty();
        verifyNoInteractions(inviteUseMapper);
    }

    @Test
    @DisplayName("生成人批量回查：系统生成（created_by=0）不参与查询")
    void usersCreatedByBatches() {
        InviteCode byMerchant = new InviteCode();
        byMerchant.setCreatedBy(USER);
        InviteCode bySystem = new InviteCode();
        bySystem.setCreatedBy(0L);
        User user = new User();
        user.setId(USER);
        when(userMapper.selectBatchIds(List.of(USER))).thenReturn(List.of(user));

        Map<Long, User> users = service.usersCreatedBy(List.of(byMerchant, bySystem));

        assertThat(users).containsEntry(USER, user);
    }

    @Test
    @DisplayName("整页都是系统生成：不查用户表")
    void usersCreatedBySkipsQueryWhenNoUsers() {
        InviteCode bySystem = new InviteCode();
        bySystem.setCreatedBy(0L);

        assertThat(service.usersCreatedBy(List.of(bySystem))).isEmpty();
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("作废成功记一条 invite.revoke，摘要写明已用次数")
    void revokeRecordsAudit() {
        InviteCode revoked = new InviteCode();
        revoked.setId(9L);
        revoked.setCode("012345");
        revoked.setStatus(InviteStatus.REVOKED);
        revoked.setMaxUses(20);
        revoked.setUsedCount(3);
        when(inviteCodeMapper.update(any(), any())).thenReturn(1);
        when(inviteCodeMapper.selectOne(any())).thenReturn(revoked);

        service.revoke("012345");

        verify(auditService).record(eq(AuditActions.TABLE_INVITE), eq(9L), eq(AuditActions.INVITE_REVOKE),
                contains("作废邀请码"));
    }

    @Test
    @DisplayName("已用完的码不能作废：作废会让人以为准入被撤销")
    void revokeUsedUpRejected() {
        InviteCode used = new InviteCode();
        used.setCode("012345");
        used.setStatus(InviteStatus.USED);
        used.setMaxUses(20);
        used.setUsedCount(20);
        when(inviteCodeMapper.update(any(), any())).thenReturn(0);
        when(inviteCodeMapper.selectOne(any())).thenReturn(used);

        assertThatThrownBy(() -> service.revoke("012345"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("次数已用完");
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("判定是否凭码进来过：查使用明细，不查邀请码表")
    void hasConsumedReadsUseDetail() {
        when(inviteUseMapper.selectCount(any())).thenReturn(1L);

        assertThat(service.hasConsumed(USER)).isTrue();
        assertThat(service.hasConsumed(null)).isFalse();
    }

    @Test
    @DisplayName("红点：未读码数与标记已读直接走 SQL")
    void unreadAndMarkRead() {
        when(inviteCodeMapper.countUnread()).thenReturn(3L);
        when(inviteCodeMapper.markRead()).thenReturn(2);

        assertThat(service.unreadCount()).isEqualTo(3L);
        assertThat(service.markRead()).isEqualTo(2);
    }
}

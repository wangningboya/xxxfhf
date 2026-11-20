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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 邀请码生成与核销。
 *
 * 核销的判定权在那条带条件的 UPDATE 上，所以这里重点测「affectedRows = 0 时给出什么提示」，
 * 而不是测「先查再改」——后者在并发下会让一个码被两个人用掉。
 */
@ExtendWith(MockitoExtension.class)
class InviteCodeServiceTest {

    private static final Long USER = 1L;

    @Mock
    private InviteCodeMapper inviteCodeMapper;

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
    }

    @Test
    @DisplayName("生成的码是 8 位，且不含易混字符 0/O/1/I/L")
    void generatedCodeIsReadable() {
        InviteCode code = service.create(24, 100L);

        assertThat(code.getCode()).hasSize(8);
        assertThat(code.getCode()).matches("^[A-HJ-KM-NP-Z2-9]{8}$");
        assertThat(code.getStatus()).isEqualTo(InviteStatus.UNUSED);
        assertThat(code.getExpireAt()).isAfter(LocalDateTime.now().plusHours(23));
        verify(inviteCodeMapper).insert(any(InviteCode.class));
        // 发码等于放人进来，必须留一条 invite.create
        verify(auditService).record(eq(AuditActions.TABLE_INVITE), any(), eq(AuditActions.INVITE_CREATE),
                contains("生成邀请码"));
    }

    @Test
    @DisplayName("不传有效期按 24 小时")
    void defaultExpireIs24Hours() {
        InviteCode code = service.create(0, 100L);

        assertThat(code.getExpireAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(code.getExpireAt()).isBefore(LocalDateTime.now().plusHours(25));
    }

    @Test
    @DisplayName("有效期超过 30 天直接拒绝")
    void expireTooLongRejected() {
        assertThatThrownBy(() -> service.create(721, 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("30 天");
        verify(inviteCodeMapper, never()).insert(any(InviteCode.class));
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
    @DisplayName("批量查使用者：跳过未使用的码，未使用的码不参与查询")
    void usersUsedByBatches() {
        InviteCode used = new InviteCode();
        used.setCode("AAAABBBB");
        used.setUsedBy(USER);
        InviteCode unused = new InviteCode();
        unused.setCode("CCCCDDDD");
        User user = new User();
        user.setId(USER);
        when(userMapper.selectBatchIds(List.of(USER))).thenReturn(List.of(user));

        Map<Long, User> users = service.usersUsedBy(List.of(used, unused));

        assertThat(users).containsEntry(USER, user);
    }

    @Test
    @DisplayName("整页都没人用过：不查用户表")
    void usersUsedBySkipsQueryWhenNoUsers() {
        InviteCode unused = new InviteCode();
        unused.setCode("CCCCDDDD");

        assertThat(service.usersUsedBy(List.of(unused))).isEmpty();
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("批量生成：按数量生成，每张都落库")
    void createBatchCreatesEachCode() {
        List<InviteCode> codes = service.createBatch(3, 24, 100L);

        assertThat(codes).hasSize(3);
        assertThat(codes).extracting(InviteCode::getCode).doesNotHaveDuplicates();
        verify(inviteCodeMapper, times(3)).insert(any(InviteCode.class));
    }

    @Test
    @DisplayName("批量生成：0 个或 21 个都拒绝，不静默截断")
    void createBatchRejectsOutOfRange() {
        assertThatThrownBy(() -> service.createBatch(0, 24, 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多生成 20");
        assertThatThrownBy(() -> service.createBatch(21, 24, 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多生成 20");
        verify(inviteCodeMapper, never()).insert(any(InviteCode.class));
    }

    @Test
    @DisplayName("作废成功记一条 invite.revoke")
    void revokeRecordsAudit() {
        InviteCode revoked = new InviteCode();
        revoked.setId(9L);
        revoked.setCode("ABCD2345");
        revoked.setStatus(InviteStatus.REVOKED);
        when(inviteCodeMapper.update(any(), any())).thenReturn(1);
        when(inviteCodeMapper.selectOne(any())).thenReturn(revoked);

        service.revoke("abcd2345");

        verify(auditService).record(eq(AuditActions.TABLE_INVITE), eq(9L), eq(AuditActions.INVITE_REVOKE),
                contains("作废邀请码"));
    }

    @Test
    @DisplayName("红点：未读条数与标记已读直接走 SQL")
    void unreadAndMarkRead() {
        when(inviteCodeMapper.countUnread()).thenReturn(3L);
        when(inviteCodeMapper.markRead()).thenReturn(2);

        assertThat(service.unreadCount()).isEqualTo(3L);
        assertThat(service.markRead()).isEqualTo(2);
    }

    @Test
    @DisplayName("空码不发 SQL")
    void blankCodeRejected() {
        assertThatThrownBy(() -> service.consume("   ", USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请输入邀请码");
        verify(inviteCodeMapper, never()).consume(anyString(), anyLong());
    }
}

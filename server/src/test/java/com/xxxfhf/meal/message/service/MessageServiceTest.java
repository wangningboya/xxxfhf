package com.xxxfhf.meal.message.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.config.AppProperties;
import com.xxxfhf.meal.message.domain.UserMessage;
import com.xxxfhf.meal.message.mapper.UserMessageMapper;
import com.xxxfhf.meal.notify.domain.NotifyType;
import com.xxxfhf.meal.order.domain.Order;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内消息的五条硬规则：
 * 用户侧只写一条给下单人、商家侧全店共享一条、别人的消息不许标已读、
 * 商家侧标已读要记下是谁点的、清理必须分批。
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private UserMessageMapper messageMapper;
    @Mock
    private UserMapper userMapper;
    /** 首页横栏条数改成读配置；本类用例不调 summary()，给出 mock 只是避免注入 null */
    @Mock
    private AppProperties appProperties;

    @InjectMocks
    private MessageService service;

    private Order order;

    /**
     * MyBatis-Plus 的 lambda 条件要先把实体的字段映射注册进 TableInfoHelper，
     * 否则 {@code User::getUserType} 会抛「can not find lambda cache」。
     * 正式运行时这一步由 mapper 扫描完成，单测里没有 Spring 上下文，只能手工注册。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, UserMessage.class);
    }

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(1L);
        order.setUserId(7L);
        order.setOrderNo("20261123-000001");
    }

    @Test
    @DisplayName("用户侧：只写下单人一条，侧别是 customer")
    void recordToCustomer() {
        service.record(order, NotifyType.ACCEPTED, "商家已接单", "红烧肉饭×2，11-24 请按时到店取餐");

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messageMapper, times(1)).insert(captor.capture());

        UserMessage saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getUserType()).isEqualTo(UserMessage.SIDE_CUSTOMER);
        assertThat(saved.getOrderId()).isEqualTo(1L);
        assertThat(saved.getMsgType()).isEqualTo("accepted");
        assertThat(saved.getTitle()).isEqualTo("商家已接单");
        // 新消息必须未读，否则铃铛从第一条起就不会亮
        assertThat(saved.getReadAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("商家侧：只写一条全店共享的消息，user_id 留空")
    void recordToMerchantShared() {
        service.record(order, NotifyType.NEW_ORDER, "新订单", "37.00 元 红烧肉饭×2，张三");

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messageMapper, times(1)).insert(captor.capture());

        UserMessage saved = captor.getValue();
        // 共享收件箱靠 user_type 认侧别，带上 user_id 就变成「某个账号的私信」了
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.getUserType()).isEqualTo(UserMessage.SIDE_MERCHANT);
        assertThat(saved.getMsgType()).isEqualTo("newOrder");
        assertThat(saved.getReadAt()).isNull();
    }

    @Test
    @DisplayName("订单还没落库（id 为空）时不写消息")
    void recordSkipsWithoutOrderId() {
        order.setId(null);

        service.record(order, NotifyType.ACCEPTED, "商家已接单", "内容");

        verify(messageMapper, never()).insert(any(UserMessage.class));
    }

    @Test
    @DisplayName("写库异常不外抛：站内消息失败不能影响接单")
    void recordSwallowsInsertFailure() {
        when(messageMapper.insert(any(UserMessage.class)))
                .thenThrow(new RuntimeException("db down"));

        service.record(order, NotifyType.ACCEPTED, "商家已接单", "内容");

        verify(messageMapper, times(1)).insert(any(UserMessage.class));
    }

    @Test
    @DisplayName("标题与内容超长按列宽截断，不靠数据库报错兜底")
    void recordTruncatesTooLongText() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append('长');
        }

        service.record(order, NotifyType.ACCEPTED, longText.toString(), longText.toString());

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(MessageService.MAX_TITLE);
        assertThat(captor.getValue().getContent()).hasSize(MessageService.MAX_CONTENT);
    }

    @Test
    @DisplayName("不能标别人消息的已读")
    void markReadRejectsOthersMessage() {
        UserMessage other = new UserMessage();
        other.setId(10L);
        other.setUserId(9L);
        other.setUserType(UserMessage.SIDE_CUSTOMER);
        when(messageMapper.selectById(10L)).thenReturn(other);

        assertThatThrownBy(() -> service.markRead(7L, "customer", 10L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能操作别人的消息");

        verify(messageMapper, never()).updateById(any(UserMessage.class));
    }

    @Test
    @DisplayName("商家侧共享消息：任何商家账号都能标，并记下是谁点的")
    void markReadOnSharedRecordsReader() {
        UserMessage shared = new UserMessage();
        shared.setId(10L);
        shared.setUserType(UserMessage.SIDE_MERCHANT);
        when(messageMapper.selectById(10L)).thenReturn(shared);

        User admin = new User();
        admin.setId(3L);
        admin.setNickName("张老板");
        when(userMapper.selectById(3L)).thenReturn(admin);

        service.markRead(3L, "merchant", 10L);

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messageMapper).updateById(captor.capture());
        UserMessage update = captor.getValue();
        assertThat(update.getReadAt()).isNotNull();
        assertThat(update.getReadById()).isEqualTo(3L);
        assertThat(update.getReadByName()).isEqualTo("张老板");
    }

    @Test
    @DisplayName("没填昵称的商家账号，用登录账号作为已读人")
    void markReadFallsBackToUsername() {
        UserMessage shared = new UserMessage();
        shared.setId(10L);
        shared.setUserType(UserMessage.SIDE_MERCHANT);
        when(messageMapper.selectById(10L)).thenReturn(shared);

        User admin = new User();
        admin.setId(3L);
        admin.setUsername("admin01");
        when(userMapper.selectById(3L)).thenReturn(admin);

        service.markRead(3L, "merchant", 10L);

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertThat(captor.getValue().getReadByName()).isEqualTo("admin01");
    }

    @Test
    @DisplayName("普通用户不能标商家侧消息的已读")
    void markReadRejectsCustomerOnMerchantSide() {
        UserMessage shared = new UserMessage();
        shared.setId(10L);
        shared.setUserType(UserMessage.SIDE_MERCHANT);
        when(messageMapper.selectById(10L)).thenReturn(shared);

        assertThatThrownBy(() -> service.markRead(7L, "customer", 10L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能操作别人的消息");

        verify(messageMapper, never()).updateById(any(UserMessage.class));
    }

    @Test
    @DisplayName("已读的消息再标一次不产生更新")
    void markReadIsIdempotent() {
        UserMessage done = new UserMessage();
        done.setId(10L);
        done.setUserId(7L);
        done.setUserType(UserMessage.SIDE_CUSTOMER);
        done.setReadAt(LocalDateTime.now().minusHours(1));
        when(messageMapper.selectById(10L)).thenReturn(done);

        service.markRead(7L, "customer", 10L);

        verify(messageMapper, never()).updateById(any(UserMessage.class));
    }

    @Test
    @DisplayName("清理按批删除，凑满一批就继续，不满即结束")
    void cleanupDeletesInBatches() {
        when(messageMapper.deleteBefore(any(LocalDateTime.class), anyInt()))
                .thenReturn(1000, 400);

        int total = service.cleanup(90, 1000);

        assertThat(total).isEqualTo(1400);
        verify(messageMapper, times(2)).deleteBefore(any(LocalDateTime.class), anyInt());
    }
}

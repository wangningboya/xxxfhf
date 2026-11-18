package com.xxxfhf.meal.order;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.dict.DictService;
import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.notify.NotifyService;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.junit.jupiter.api.BeforeEach;
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
 * 联系顾客号码的四个约束：结束太久不给、调太频不给、没号不给、给的时候要留痕。
 */
@ExtendWith(MockitoExtension.class)
class OrderAdminServiceContactPhoneTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ServeDateClock clock;
    @Mock
    private NotifyService notifyService;
    @Mock
    private OrderStatusLogService statusLogService;
    @Mock
    private OrderStatusLogMapper statusLogMapper;
    @Mock
    private DictService dictService;

    @InjectMocks
    private OrderAdminService service;

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId("order-1");
        order.setOrderNo("20261118-000001");
        order.setUserId("user-1");
        order.setStatus(OrderStatus.ACCEPTED);
        order.setAddressSnapshot(new AddressSnapshot("张三", "13812341234", "1 号院", "A 区", null));
    }

    @Test
    @DisplayName("订单结束超过 7 天：不再提供号码")
    void rejectsAfterWindow() {
        order.setStatus(OrderStatus.COMPLETED);
        when(orderService.require("order-1")).thenReturn(order);

        OrderStatusLog finished = new OrderStatusLog();
        finished.setCreatedAt(LocalDateTime.now().minusDays(8));
        when(statusLogMapper.selectOne(any())).thenReturn(finished);

        assertThatThrownBy(() -> service.contactPhone("merchant-1", "order-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过 7 天");
        verify(statusLogService, never()).record(anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("同一单 24 小时内调够 5 次：拒绝并提示稍后再试")
    void rejectsTooFrequent() {
        when(orderService.require("order-1")).thenReturn(order);
        when(statusLogMapper.selectCount(any())).thenReturn(5L);

        assertThatThrownBy(() -> service.contactPhone("merchant-1", "order-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("24 小时");
    }

    @Test
    @DisplayName("快照和账号都没留号：404 而不是返回空字符串")
    void rejectsWhenNoPhone() {
        order.setAddressSnapshot(null);
        when(orderService.require("order-1")).thenReturn(order);
        when(statusLogMapper.selectCount(any())).thenReturn(0L);

        User user = new User();
        user.setId("user-1");
        when(userMapper.selectById("user-1")).thenReturn(user);

        assertThatThrownBy(() -> service.contactPhone("merchant-1", "order-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有留联系电话");
    }

    @Test
    @DisplayName("正常调取：返回明文并写一条时间轴记录（不含号码本身）")
    void returnsPhoneAndLogs() {
        when(orderService.require("order-1")).thenReturn(order);
        when(statusLogMapper.selectCount(any())).thenReturn(0L);

        String phone = service.contactPhone("merchant-1", "order-1");

        assertThat(phone).isEqualTo("13812341234");
        verify(statusLogService).record(eq("order-1"), eq(OrderLogActions.EVENT_CONTACT_PHONE),
                any(), any(), eq("调取号码拨号"), any());
    }

    @Test
    @DisplayName("快照没号时回退到账号手机号")
    void fallsBackToAccountPhone() {
        order.setAddressSnapshot(new AddressSnapshot("张三", null, null, null, null));
        when(orderService.require("order-1")).thenReturn(order);
        when(statusLogMapper.selectCount(any())).thenReturn(0L);

        User user = new User();
        user.setId("user-1");
        user.setPhone(" 13912345678 ");
        when(userMapper.selectById("user-1")).thenReturn(user);

        assertThat(service.contactPhone("merchant-1", "order-1")).isEqualTo("13912345678");
    }
}

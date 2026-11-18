package com.xxxfhf.meal.user;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xxxfhf.meal.address.Address;
import com.xxxfhf.meal.address.AddressMapper;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.order.AddressSnapshot;
import com.xxxfhf.meal.order.Order;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xxxfhf.meal.order.OrderMapper;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注销的两条分支：没订单整行删，有订单匿名化。另有商家账号拒注销。
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private AddressMapper addressMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private AccountDeletionService service;

    private User customer;

    /**
     * 单测不起 Spring 容器，MyBatis-Plus 的 lambda 列名缓存不会自动建立；
     * 而注销链路里有两处 {@code set(...)} / {@code select(...)} 会立即解析列名。
     */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, Address.class);
    }

    @BeforeEach
    void setUp() {
        customer = new User();
        customer.setId("user-000001");
        customer.setUserType(UserType.CUSTOMER);
        customer.setNickName("张三");
        customer.setPhone("13812341234");
        customer.setAuditStatus(AuditStatus.APPROVED);
        customer.setTokenVersion(0);
    }

    @Test
    @DisplayName("没下过单：整行删除账号与地址，并留一条注销记录")
    void deletesWhenNoOrders() {
        when(userMapper.selectById("user-000001")).thenReturn(customer);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(addressMapper.delete(any())).thenReturn(2);

        AccountDeletionService.DeletionResult result = service.delete("user-000001");

        assertThat(result.mode()).isEqualTo(AccountDeletionService.MODE_DELETE);
        assertThat(result.ordersKept()).isZero();
        assertThat(result.addressesRemoved()).isEqualTo(2);
        verify(userMapper).deleteById("user-000001");
        verify(auditService).record(eq(AuditActions.TABLE_USER), eq("user-000001"),
                eq(AuditActions.USER_SELF_DELETE), anyString());
    }

    @Test
    @DisplayName("有订单：保留行并匿名化，订单快照电话改掩码，令牌版本 +1 踢下线")
    @SuppressWarnings("unchecked")
    void anonymizesWhenHasOrders() {
        when(userMapper.selectById("user-000001")).thenReturn(customer);
        when(orderMapper.selectCount(any())).thenReturn(2L);
        when(addressMapper.delete(any())).thenReturn(1);

        Order order = new Order();
        order.setId("order-1");
        order.setAddressSnapshot(new AddressSnapshot("张三", "13812341234", "1 号院", "A 区", null));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));

        AccountDeletionService.DeletionResult result = service.delete("user-000001");

        assertThat(result.mode()).isEqualTo(AccountDeletionService.MODE_ANONYMIZE);
        assertThat(result.ordersKept()).isEqualTo(2);
        // 用户行只置空、不删除
        verify(userMapper, never()).deleteById("user-000001");
        verify(userMapper).update(isNull(), any(Wrapper.class));

        ArgumentCaptor<Order> patch = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(patch.capture());
        assertThat(patch.getValue().getAddressSnapshot().phone()).isEqualTo("138******34");
        assertThat(patch.getValue().getAddressSnapshot().contactName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("商家账号不能在这里注销")
    void rejectsMerchant() {
        customer.setUserType(UserType.MERCHANT);
        when(userMapper.selectById("user-000001")).thenReturn(customer);

        assertThatThrownBy(() -> service.delete("user-000001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商家账号");
        verify(addressMapper, never()).delete(any());
    }

    @Test
    @DisplayName("预览：有订单时告诉用户会匿名化，没订单时告知整行删除")
    void previewTellsWhatWillHappen() {
        when(userMapper.selectById("user-000001")).thenReturn(customer);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(addressMapper.selectCount(any())).thenReturn(3L);

        AccountDeletionService.DeletionPreview preview = service.preview("user-000001");

        assertThat(preview.mode()).isEqualTo(AccountDeletionService.MODE_DELETE);
        assertThat(preview.addressCount()).isEqualTo(3);
        assertThat(preview.note()).contains("3 年");

        when(orderMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.preview("user-000001").mode()).isEqualTo(AccountDeletionService.MODE_ANONYMIZE);
    }
}

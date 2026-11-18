package com.xxxfhf.meal;

import com.xxxfhf.meal.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单状态机迁移矩阵。
 *
 * 重点钉两件容易写错的事：
 * 1. **没有「制作中」**，接单后一步到 ready
 * 2. **ready 由自取与配送共用**，取送方式只影响文案，不影响路径 ——
 *    「配送中」是个说法，不是状态值，推不到它
 */
@DisplayName("订单状态机")
class OrderStatusTest {

    private static final String PICKUP = OrderStatus.TYPE_PICKUP;
    private static final String DELIVERY = OrderStatus.TYPE_DELIVERY;

    @Test
    @DisplayName("一条直线：已接单 → ready → 已完成")
    void straightPath() {
        assertThat(OrderStatus.canAdvance(OrderStatus.ACCEPTED, OrderStatus.READY)).isTrue();
        assertThat(OrderStatus.canAdvance(OrderStatus.READY, OrderStatus.COMPLETED)).isTrue();
        assertThat(OrderStatus.nextOf(OrderStatus.ACCEPTED)).isEqualTo(OrderStatus.READY);
        assertThat(OrderStatus.nextOf(OrderStatus.READY)).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("取送方式不改变路径：配送单的下一步同样是 ready")
    void deliverySharesReady() {
        // 状态机不认识 delivering —— 库里有这个值只能是历史脏数据，推进时会被直接拒掉
        assertThat(OrderStatus.canAdvance(OrderStatus.ACCEPTED, "delivering")).isFalse();
        assertThat(OrderStatus.canAdvance("delivering", OrderStatus.COMPLETED)).isFalse();
        assertThat(OrderStatus.nextOf(OrderStatus.ACCEPTED)).isEqualTo(OrderStatus.READY);
    }

    @Test
    @DisplayName("不能跳步：已接单推不到已完成，ready 也不能回头")
    void cannotSkipOrGoBack() {
        assertThat(OrderStatus.canAdvance(OrderStatus.ACCEPTED, OrderStatus.COMPLETED)).isFalse();
        assertThat(OrderStatus.canAdvance(OrderStatus.READY, OrderStatus.ACCEPTED)).isFalse();
        assertThat(OrderStatus.canAdvance(OrderStatus.PENDING_ACCEPT, OrderStatus.READY)).isFalse();
    }

    @Test
    @DisplayName("待接单与终态都不能推进，也没有下一步")
    void noAdvanceFromEnds() {
        for (String status : new String[]{OrderStatus.PENDING_ACCEPT, OrderStatus.COMPLETED,
                OrderStatus.CANCELLED, OrderStatus.REJECTED}) {
            assertThat(OrderStatus.nextOf(status)).isNull();
            assertThat(OrderStatus.canAdvance(status, OrderStatus.COMPLETED)).isFalse();
        }
    }

    @Test
    @DisplayName("取餐码核销认已接单与 ready，配送单同样可用码核对")
    void pickupCodeScope() {
        assertThat(OrderStatus.canPickup(OrderStatus.ACCEPTED)).isTrue();
        assertThat(OrderStatus.canPickup(OrderStatus.READY)).isTrue();
        assertThat(OrderStatus.canPickup(OrderStatus.PENDING_ACCEPT)).isFalse();
        assertThat(OrderStatus.canPickup(OrderStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("备菜计入已接单及之后，不含待接单")
    void prepScope() {
        assertThat(OrderStatus.PREP_INCLUDED)
                .containsExactlyInAnyOrder(OrderStatus.ACCEPTED, OrderStatus.READY,
                        OrderStatus.COMPLETED)
                .doesNotContain(OrderStatus.PENDING_ACCEPT);
    }

    @Test
    @DisplayName("ready 两套文案：自取看「待取餐」，配送看「配送中」")
    void readyLabelByDeliveryType() {
        assertThat(OrderStatus.label(OrderStatus.READY, PICKUP)).isEqualTo("待取餐");
        assertThat(OrderStatus.label(OrderStatus.READY, DELIVERY)).isEqualTo("配送中");
        // 老数据没写取送方式时按自取兜底，不能出现空标签
        assertThat(OrderStatus.label(OrderStatus.READY, null)).isEqualTo("待取餐");
    }

    @Test
    @DisplayName("其余状态的中文名与取送方式无关")
    void otherLabelsIgnoreDeliveryType() {
        for (String status : new String[]{OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED,
                OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.REJECTED}) {
            assertThat(OrderStatus.label(status, PICKUP))
                    .isEqualTo(OrderStatus.label(status, DELIVERY));
        }
        assertThat(OrderStatus.label(OrderStatus.ACCEPTED, DELIVERY)).isEqualTo("已接单");
    }

    @Test
    @DisplayName("状态中文名兜底：没有制作中，未知值原样回显，null 不抛异常")
    void labelFallbacks() {
        assertThat(OrderStatus.label("making")).isEqualTo("making");
        assertThat(OrderStatus.label(null)).isNull();
        assertThat(OrderStatus.label(OrderStatus.COMPLETED)).isEqualTo("已完成");
    }
}

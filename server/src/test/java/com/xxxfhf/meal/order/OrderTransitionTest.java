package com.xxxfhf.meal.order;

import com.xxxfhf.meal.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单状态转移表。
 *
 * <p>这张表是「哪条转移允许从哪些状态出发」的唯一事实来源：Service 里的校验与条件更新的
 * {@code .in(status, ...)} 都从这里取。所以这里钉三件事：
 * 1. 每条转移的源状态集合与目标状态（表格本身不能写错）；
 * 2. 非法起点一律抛「只有…的订单可以…」，且文案逐字稳定（前端可能直接展示）；
 * 3. 手动推进只认 READY / COMPLETE 两条线，核销、取消各有独立入口，不能从这里绕过去。
 */
@DisplayName("订单状态转移表")
class OrderTransitionTest {

    private static final String PICKUP = OrderStatus.TYPE_PICKUP;
    private static final String DELIVERY = OrderStatus.TYPE_DELIVERY;

    @Test
    @DisplayName("接单与拒单：只从待接单出发")
    void acceptAndReject() {
        assertThat(OrderTransition.ACCEPT.allows(OrderStatus.PENDING_ACCEPT)).isTrue();
        assertThat(OrderTransition.ACCEPT.target()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(OrderTransition.ACCEPT.logAction()).isEqualTo(OrderLogActions.ORDER_ACCEPT);

        assertThat(OrderTransition.REJECT.allows(OrderStatus.PENDING_ACCEPT)).isTrue();
        assertThat(OrderTransition.REJECT.target()).isEqualTo(OrderStatus.REJECTED);
        assertThat(OrderTransition.REJECT.logAction()).isEqualTo(OrderLogActions.ORDER_REJECT);

        // 已接单不能再接一次、也不能事后拒单
        assertThat(OrderTransition.ACCEPT.allows(OrderStatus.ACCEPTED)).isFalse();
        assertThat(OrderTransition.REJECT.allows(OrderStatus.ACCEPTED)).isFalse();
        assertThat(OrderTransition.ACCEPT.allows(null)).isFalse();
    }

    @Test
    @DisplayName("一条直线：已接单 → 出餐 → 完成，不能跳步")
    void readyAndComplete() {
        assertThat(OrderTransition.READY.allows(OrderStatus.ACCEPTED)).isTrue();
        assertThat(OrderTransition.READY.target()).isEqualTo(OrderStatus.READY);
        assertThat(OrderTransition.COMPLETE.allows(OrderStatus.READY)).isTrue();
        assertThat(OrderTransition.COMPLETE.target()).isEqualTo(OrderStatus.COMPLETED);
        // 已接单直接完成 = 跳过出餐，商家会漏做一份
        assertThat(OrderTransition.COMPLETE.allows(OrderStatus.ACCEPTED)).isFalse();
        assertThat(OrderTransition.READY.allows(OrderStatus.PENDING_ACCEPT)).isFalse();
    }

    @Test
    @DisplayName("核销与完成目标态相同，但动作码要分得开")
    void pickupSharesTargetWithComplete() {
        assertThat(OrderTransition.PICKUP.allows(OrderStatus.READY)).isTrue();
        assertThat(OrderTransition.PICKUP.allows(OrderStatus.ACCEPTED)).isFalse();
        assertThat(OrderTransition.PICKUP.target()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(OrderTransition.COMPLETE.target()).isEqualTo(OrderStatus.COMPLETED);
        // 事后要分得清这单是输码核对的，还是商家直接点了「完成订单」
        assertThat(OrderTransition.PICKUP.logAction()).isEqualTo(OrderLogActions.ORDER_PICKUP);
        assertThat(OrderTransition.COMPLETE.logAction()).isEqualTo(OrderLogActions.ORDER_COMPLETE);
    }

    @Test
    @DisplayName("用户自助取消：只有截单前的待接单能走")
    void cancelByUser() {
        assertThat(OrderTransition.CANCEL_BY_USER.allows(OrderStatus.PENDING_ACCEPT)).isTrue();
        assertThat(OrderTransition.CANCEL_BY_USER.target()).isEqualTo(OrderStatus.CANCELLED);
        // 已接单及之后只能找商家，截单后只能提申请 —— 这两条都不在这张转移里
        assertThat(OrderTransition.CANCEL_BY_USER.allows(OrderStatus.ACCEPTED)).isFalse();
        assertThat(OrderTransition.CANCEL_BY_USER.allows(OrderStatus.READY)).isFalse();
        assertThat(OrderTransition.CANCEL_BY_USER.allows(OrderStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("审批取消申请：待接单/已接单/待取餐都能批，终态不能")
    void cancelApproved() {
        for (String status : new String[]{OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED,
                OrderStatus.READY}) {
            assertThat(OrderTransition.CANCEL_APPROVED.allows(status)).isTrue();
        }
        assertThat(OrderTransition.CANCEL_APPROVED.target()).isEqualTo(OrderStatus.CANCELLED);
        // 终态单上挂着申请属于异常数据：此时同意会二次释放份数，宁可报错让人来处理
        for (String status : new String[]{OrderStatus.COMPLETED, OrderStatus.CANCELLED,
                OrderStatus.REJECTED}) {
            assertThat(OrderTransition.CANCEL_APPROVED.allows(status)).isFalse();
        }
    }

    @Test
    @DisplayName("手动推进只认 出餐/完成 两条线，不能绕到取消")
    void advanceOnlyReadyAndComplete() {
        assertThat(OrderTransition.advance(OrderStatus.ACCEPTED, OrderStatus.READY))
                .contains(OrderTransition.READY);
        assertThat(OrderTransition.advance(OrderStatus.READY, OrderStatus.COMPLETED))
                .contains(OrderTransition.COMPLETE);
        // 跳步
        assertThat(OrderTransition.advance(OrderStatus.ACCEPTED, OrderStatus.COMPLETED)).isEmpty();
        // 绕过取消逻辑直接把单推成已取消 —— 这正是不能允许的走法
        assertThat(OrderTransition.advance(OrderStatus.PENDING_ACCEPT, OrderStatus.CANCELLED)).isEmpty();
        assertThat(OrderTransition.advance(OrderStatus.ACCEPTED, OrderStatus.CANCELLED)).isEmpty();
        assertThat(OrderTransition.advance(OrderStatus.COMPLETED, OrderStatus.COMPLETED)).isEmpty();
    }

    @Test
    @DisplayName("下一步：待接单与终态没有下一步")
    void nextOf() {
        assertThat(OrderTransition.nextOf(OrderStatus.ACCEPTED)).isEqualTo(OrderStatus.READY);
        assertThat(OrderTransition.nextOf(OrderStatus.READY)).isEqualTo(OrderStatus.COMPLETED);
        for (String status : new String[]{OrderStatus.PENDING_ACCEPT, OrderStatus.COMPLETED,
                OrderStatus.CANCELLED, OrderStatus.REJECTED}) {
            assertThat(OrderTransition.nextOf(status)).isNull();
        }
    }

    @Test
    @DisplayName("提示文案：来源状态与当前状态都翻成中文，且顺序固定")
    void requireMessages() {
        assertThatThrownBy(() -> OrderTransition.READY.require(OrderStatus.COMPLETED))
                .isInstanceOf(BizException.class)
                .hasMessage("只有已接单的订单可以出餐，当前状态是「已完成」");

        assertThatThrownBy(() -> OrderTransition.ACCEPT.require(OrderStatus.ACCEPTED))
                .isInstanceOf(BizException.class)
                .hasMessage("只有待接单的订单可以接单，当前状态是「已接单」");

        // 多源状态按生命周期排序，不按 Set 的哈希顺序
        assertThatThrownBy(() -> OrderTransition.CANCEL_APPROVED.require(
                OrderStatus.COMPLETED, PICKUP))
                .isInstanceOf(BizException.class)
                .hasMessage("只有待接单、已接单、待取餐的订单可以取消订单，当前状态是「已完成」");

        // 配送单上的 ready 叫「配送中」，提示语跟着取送方式变
        assertThatThrownBy(() -> OrderTransition.CANCEL_APPROVED.require(
                OrderStatus.COMPLETED, DELIVERY))
                .isInstanceOf(BizException.class)
                .hasMessage("只有待接单、已接单、配送中的订单可以取消订单，当前状态是「已完成」");
    }

    @Test
    @DisplayName("合法起点不抛异常")
    void requirePassesOnLegalStatus() {
        OrderTransition.ACCEPT.require(OrderStatus.PENDING_ACCEPT);
        OrderTransition.READY.require(OrderStatus.ACCEPTED, PICKUP);
        OrderTransition.CANCEL_APPROVED.require(OrderStatus.READY, DELIVERY);
    }
}

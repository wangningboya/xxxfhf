package com.xxxfhf.meal.menu.service;

import com.xxxfhf.meal.order.domain.Order;
import com.xxxfhf.meal.order.domain.OrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月销量的累加口径。
 *
 * <p>只测纯函数 {@link ComboSalesService#sumByCombo}：查库条件（月份区间、状态）由
 * 调用方那次 {@code selectList} 负责，这里盯的是「同一个盒饭多行怎么加」和「脏数据不能炸」。
 */
class ComboSalesServiceTest {

    private static Order order(OrderItem... items) {
        Order order = new Order();
        order.setItems(List.of(items));
        return order;
    }

    private static OrderItem item(Long comboId, Integer qty) {
        return new OrderItem(comboId, "A餐", 1800, qty, List.of(), List.of());
    }

    @Test
    @DisplayName("同一单里同一个盒饭选了两套规格：按份数相加，不是按行数")
    void sumsQtyWhenSameComboHasTwoSpecRows() {
        // 白米饭 3 份 + 杂粮饭 2 份，是一单里的两行，不是两单
        Map<Long, Integer> sold = ComboSalesService.sumByCombo(
                List.of(order(item(1L, 3), item(1L, 2))));

        assertThat(sold).containsEntry(1L, 5);
    }

    @Test
    @DisplayName("跨多单累加，不同盒饭各算各的")
    void sumsAcrossOrders() {
        Map<Long, Integer> sold = ComboSalesService.sumByCombo(List.of(
                order(item(1L, 2), item(2L, 1)),
                order(item(1L, 4))));

        assertThat(sold).containsEntry(1L, 6).containsEntry(2L, 1);
    }

    @Test
    @DisplayName("items 为 null（早期订单）跳过，不抛")
    void skipsNullItems() {
        Order legacy = new Order();
        legacy.setItems(null);

        assertThat(ComboSalesService.sumByCombo(List.of(legacy))).isEmpty();
        assertThat(ComboSalesService.sumByCombo(null)).isEmpty();
    }

    @Test
    @DisplayName("comboId 为空、qty 为空或非正数的行跳过，不产生 key")
    void skipsBrokenRows() {
        Map<Long, Integer> sold = ComboSalesService.sumByCombo(List.of(
                order(item(null, 2), item(1L, null), item(1L, 0), item(1L, -3), item(2L, 1))));

        assertThat(sold).containsOnlyKeys(2L).containsEntry(2L, 1);
    }
}

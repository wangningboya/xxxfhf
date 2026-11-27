package com.xxxfhf.meal.menu.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.order.domain.Order;
import com.xxxfhf.meal.order.domain.OrderItem;
import com.xxxfhf.meal.order.domain.OrderStatus;
import com.xxxfhf.meal.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盒饭「月售」—— 菜单封面角标上的一个展示字段。
 *
 * 口径三条（产品已定，别在别处再写一份）：
 * 1. 维度是 combo，累加 {@code items[].qty}。同一单同一个盒饭选了两套规格就是两行，
 *    按行数算会少一半，必须按份数累加
 * 2. 只算 {@link OrderStatus#PREP_INCLUDED}（已接单及之后），与备菜统计同口径：
 *    待接单不亮，商家接完单数字才涨
 * 3. 「本月」是自然月，按 {@code serve_date} 归属，不是 {@code created_at}：
 *    11 月 30 日下的 12 月 1 日的单，算 12 月
 *
 * <p>为什么不写进 {@link MenuService}：那个类的类注释写着「不碰份数占用与订单」。
 * 单开一个类，菜单域里「谁读订单」一眼可数；以后后台报表要复用，直接注入这个 Bean 即可。
 *
 * <p>为什么不落统计表：计数器要在接单 / 取消 / 拒单 / 超时释放每条路径上打补丁，漏一条就永久错。
 * 单店 300 单/天上限、一个月约 9k 行，只读两列扫一遍比维护一张会漂移的计数表便宜。
 *
 * <p>缓存：进程内、按月份为 key、TTL 一分钟。项目不引 Redis，单店单实例部署，
 * 多实例时各算各的、最多差一个 TTL，可接受。
 */
@Service
public class ComboSalesService {

    /**
     * 角标数字晚一分钟可以接受：它只是「卖得好不好」的参考，不参与任何判定，
     * 也没有人会拿它和「已订 X / 共 Y」对账（那是两个口径，见 {@code remainQty}）。
     * 9k 行 JSON 解析约 100~250ms，全量算一次比让每次进点餐页都付这个代价划算得多。
     */
    private static final long TTL_MS = 60_000L;

    private final OrderMapper orderMapper;
    private final ServeDateClock clock;

    /** 只留当月这一份，跨月时把旧 key 清掉，不需要任何淘汰策略 */
    private final Map<YearMonth, CacheEntry> cache = new ConcurrentHashMap<>();

    public ComboSalesService(OrderMapper orderMapper, ServeDateClock clock) {
        this.orderMapper = orderMapper;
        this.clock = clock;
    }

    /**
     * 本月各盒饭已售份数，key = comboId。
     *
     * <p>本月一份没卖过的盒饭**不在 map 里**，调用方用 {@code getOrDefault(id, 0)}；
     * 返回的 map 是只读的，调用方不要再往里写。
     */
    public Map<Long, Integer> monthSoldByCombo() {
        YearMonth month = YearMonth.from(clock.today());
        long now = System.currentTimeMillis();

        CacheEntry cached = cache.get(month);
        if (cached != null && cached.expiresAt() > now) {
            return cached.qty();
        }

        Map<Long, Integer> qty = Map.copyOf(sumByCombo(ordersOf(month)));
        cache.put(month, new CacheEntry(qty, now + TTL_MS));
        // 跨月只留当月：旧月份的数据再也不会被读到，留着只是占内存
        cache.keySet().removeIf(key -> !key.equals(month));
        return qty;
    }

    /**
     * 当月订单（只取 id 与 items 两列）。
     *
     * <p>区间左闭右开：{@code >= 月初} 且 {@code < 下月初}，不用 {@code <= 月末} ——
     * 月末边界少算一天是这类统计最常见的事故。
     */
    private List<Order> ordersOf(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .select(Order::getId, Order::getItems)
                .ge(Order::getServeDate, from)
                .lt(Order::getServeDate, to)
                .in(Order::getStatus, OrderStatus.PREP_INCLUDED));
    }

    /**
     * 按 comboId 累加份数。纯函数，单测直接喂订单对象，不连库。
     *
     * <p>三处跳过都是真实存在的脏数据：items 为 NULL（早期单）、comboId 为空、
     * qty 为空或非正数。跳过而不是抛 —— 一个历史脏行不该让整个点餐页打不开。
     */
    static Map<Long, Integer> sumByCombo(List<Order> orders) {
        Map<Long, Integer> qty = new HashMap<>();
        if (orders == null) {
            return qty;
        }
        for (Order order : orders) {
            if (order == null || order.getItems() == null) {
                continue;
            }
            for (OrderItem item : order.getItems()) {
                if (item == null || item.comboId() == null || item.qtyOrZero() <= 0) {
                    continue;
                }
                qty.merge(item.comboId(), item.qtyOrZero(), Integer::sum);
            }
        }
        return qty;
    }

    private record CacheEntry(Map<Long, Integer> qty, long expiresAt) {
    }
}

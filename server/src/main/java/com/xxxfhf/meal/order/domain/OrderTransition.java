package com.xxxfhf.meal.order.domain;

import com.xxxfhf.meal.common.BizException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单状态转移表：**谁能从哪个状态走到哪个状态，只在这里定义一次**。
 *
 * <p>为什么要有这张表：主状态 6 个值、7 条转移、6 个入口（接单 / 拒单 / 出餐 / 完成 / 核销 /
 * 取消），而「允许的前置状态」原先分散在各 Service 的 if 与 {@code .eq(status, ...)} 里。
 * 加一个状态时编译器不会提醒漏改哪一处，只能靠人把六个入口逐个翻一遍 ——
 * 这就是「某条路径能进、另一条进不去」的来源。
 *
 * <p>表里只放**不变量**：源状态集合、目标状态、时间轴动作码、中文动作名。
 * 副作用（释放份数、转待退款、发订阅消息）仍留在 Service —— 它们依赖事务与外部调用，
 * 塞进这张表会让它变成第二套流程实现。
 *
 * <p>刻意不入表的两种：<br>
 * 1. 取消申请（cancel_requested）不改主状态，是事件不是转移；<br>
 * 2. 超时释放份数（stock_released）同样不改主状态，只是份数副作用。
 *
 * <p>派生关系：{@link OrderStatus#canAdvance} / {@link OrderStatus#nextOf} /
 * {@link OrderStatus#canPickup} 都委托到这里，所以状态机只有这一份事实。
 */
public enum OrderTransition {

    ACCEPT("接单", OrderLogActions.ORDER_ACCEPT,
            Set.of(OrderStatus.PENDING_ACCEPT), OrderStatus.ACCEPTED),

    REJECT("拒单", OrderLogActions.ORDER_REJECT,
            Set.of(OrderStatus.PENDING_ACCEPT), OrderStatus.REJECTED),

    READY("出餐", OrderLogActions.ORDER_READY,
            Set.of(OrderStatus.ACCEPTED), OrderStatus.READY),

    COMPLETE("完成订单", OrderLogActions.ORDER_COMPLETE,
            Set.of(OrderStatus.READY), OrderStatus.COMPLETED),

    /**
     * 取餐码核销。目标态与 {@link #COMPLETE} 相同，动作码不同 ——
     * 事后要分得清这单是输码核对的，还是直接点了按钮。
     */
    PICKUP("核销", OrderLogActions.ORDER_PICKUP,
            Set.of(OrderStatus.READY), OrderStatus.COMPLETED),

    /**
     * 用户自助取消（需求 4.9 三档规则）。
     *
     * <p>允许的源状态是**待接单与已接单**：待接单不分割单时间，已接单则要再过一道截单判定 ——
     * 截单前商家接了单但餐还没备料，直接取消；截单后料已备下去，只能提交申请等商家批
     * （截单判定依赖菜单配置，属业务规则，留在 {@code OrderService.cancel}）。
     *
     * <p>出餐（ready）及之后一律不在列：餐已做好或已交付，系统不再接受任何取消。
     */
    CANCEL_BY_USER("取消订单", OrderLogActions.ORDER_CANCEL,
            Set.of(OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED), OrderStatus.CANCELLED),

    /**
     * 商家（或超时任务）同意用户的取消申请。
     *
     * <p>已接单、已出餐都能批 —— 用户申请后商家可能先接了单，这单仍然要能取消。
     * 终态不在允许集合里：已完成/已拒单的单子上挂着申请属于异常数据，
     * 此时同意会二次释放份数，宁可报错让人来处理。
     */
    CANCEL_APPROVED("取消订单", OrderLogActions.ORDER_CANCEL,
            Set.of(OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED, OrderStatus.READY),
            OrderStatus.CANCELLED);

    /** 商家手动「推进」接口能走的两条线。取餐核销有独立入口，不在其中。 */
    private static final Set<OrderTransition> ADVANCE = EnumSet.of(READY, COMPLETE);

    /**
     * 源状态在提示语里的展示顺序，按订单生命周期排。
     *
     * <p>不能直接用 {@code fromStatuses} 迭代：{@link Set#of} 不保证顺序，
     * 同一句提示在一台机器上可能换个顺序出现，前端截图对不上。
     */
    private static final List<String> DISPLAY_ORDER = List.of(
            OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED, OrderStatus.READY,
            OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.REJECTED);

    private final String actionName;
    private final String logAction;
    private final Set<String> fromStatuses;
    private final String target;

    OrderTransition(String actionName, String logAction, Set<String> fromStatuses, String target) {
        this.actionName = actionName;
        this.logAction = logAction;
        this.fromStatuses = fromStatuses;
        this.target = target;
    }

    /** 中文动作名，用于拼接「只有…可以…」这类提示。 */
    public String actionName() {
        return actionName;
    }

    /** 时间轴动作码（{@link OrderLogActions}），写入 order_status_log.action。 */
    public String logAction() {
        return logAction;
    }

    /** 允许的源状态。可直接用于条件更新的 {@code .in(Order::getStatus, ...)}。 */
    public Set<String> fromStatuses() {
        return fromStatuses;
    }

    public String target() {
        return target;
    }

    /** 该状态是否在允许的源状态里。null 一律视为不允许（Set.of 的 contains(null) 会抛 NPE）。 */
    public boolean allows(String status) {
        return status != null && fromStatuses.contains(status);
    }

    /**
     * 校验前置状态，不合法时抛出可直接展示的冲突提示。
     *
     * <p>文案由表里的源状态与动作名拼出来，不再各入口手写一份 ——
     * 手写的那些只要有人复制粘贴时改漏一个字，用户就会看到前后不一致的说法。
     */
    public void require(String status) {
        require(status, null);
    }

    /**
     * 同上，但按取送方式给状态上色（ready 在配送单上叫「配送中」）。
     *
     * @param deliveryType pickup / delivery，null 按自取处理
     */
    public void require(String status, String deliveryType) {
        if (allows(status)) {
            return;
        }
        throw BizException.conflict("只有" + fromText(deliveryType) + "的订单可以" + actionName
                + "，当前状态是「" + OrderStatus.label(status, deliveryType) + "」");
    }

    /**
     * 商家手动推进：from → to 命中哪条转移。
     *
     * <p>只认 {@link #ADVANCE} 两条线，避免「推进到已取消」这种绕过取消逻辑的走法。
     */
    public static Optional<OrderTransition> advance(String from, String to) {
        return ADVANCE.stream()
                .filter(t -> t.allows(from) && t.target().equals(to))
                .findFirst();
    }

    /** 该状态下商家「下一步」的目标状态；没有可推进的返回 null。 */
    public static String nextOf(String from) {
        return ADVANCE.stream()
                .filter(t -> t.allows(from))
                .map(OrderTransition::target)
                .findFirst()
                .orElse(null);
    }

    private String fromText(String deliveryType) {
        return DISPLAY_ORDER.stream()
                .filter(fromStatuses::contains)
                .map(status -> OrderStatus.label(status, deliveryType))
                .collect(Collectors.joining("、"));
    }
}

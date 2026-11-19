package com.xxxfhf.meal.order;

import java.util.Set;

/**
 * 订单主状态与迁移规则。与需求 4.9 的状态机一一对应。
 *
 * 迁移规则写在这里而不是散落在各个 Service 里：状态机是这张表最硬的约束，
 * 判定散开之后，加一个状态就会出现「某条路径能进、另一条进不去」的分裂。
 *
 * **没有「制作中」，自取与配送也不分叉**：接单后一步到 {@link #READY}，
 * 自取的人看到「待取餐」、配送的人看到「配送中」—— 那是同一个状态，
 * 只是 {@link #label(String, String)} 按取送方式换个说法。
 * 不为一句文案多存一个状态值：多一个值，库里就有两套并行数据要迁移、要防漏判。
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    public static final String PENDING_ACCEPT = "pending_accept";
    public static final String ACCEPTED = "accepted";
    /** 出餐完成：自取叫「待取餐」，配送叫「配送中」。同一状态，文案按取送方式定。 */
    public static final String READY = "ready";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";
    public static final String REJECTED = "rejected";

    /** 配送方式：自取 */
    public static final String TYPE_PICKUP = "pickup";
    /** 配送方式：配送 */
    public static final String TYPE_DELIVERY = "delivery";

    /**
     * 计入备菜的状态：**已接单及之后**。
     * 待接单不计入 —— 商家接完单后备菜数字才增加，这是需求第六章明确的口径。
     */
    public static final Set<String> PREP_INCLUDED = Set.of(ACCEPTED, READY, COMPLETED);

    /** 终态：不再有任何流转。 */
    public static boolean isFinal(String status) {
        return COMPLETED.equals(status) || CANCELLED.equals(status) || REJECTED.equals(status);
    }

    /** 份数是否仍被这笔订单占着。取消 / 拒单后释放。 */
    public static boolean holdsStock(String status) {
        return !CANCELLED.equals(status) && !REJECTED.equals(status);
    }

    /**
     * 商家可推进的目标状态白名单。不含接单与拒单（那两个有独立入口与额外校验）。
     *
     * 一条直线：已接单 → 出餐（ready）→ 已完成。取送方式不改变路径，
     * 只改变 ready 这个状态在界面上叫什么。
     *
     * <p>判定委托给 {@link OrderTransition}：接口层、审单、定时任务都从这里问，
     * 状态机只保留一份事实。
     */
    public static boolean canAdvance(String from, String to) {
        return OrderTransition.advance(from, to).isPresent();
    }

    /** 该状态下商家「下一步」的目标状态。没有可推进的返回 null。 */
    public static String nextOf(String from) {
        return OrderTransition.nextOf(from);
    }

    /**
     * 该状态能否用 4 位取餐码核销。
     *
     * 只认「出餐（ready）」：待接单还没做、已接单还没出餐，直接核销等于跳过了
     * 「出餐到完工」这条状态机，凭空出餐；已完成的不再重复核销。
     * 自取单与配送单同一口径 —— 码印在餐盒上，是商家核对「这盒是不是这一单」的
     * 凭据，不发给用户不代表不能用，但同样得等餐出来了再核。
     *
     * <p>委托给 {@link OrderTransition#PICKUP}，与核销接口用的是同一条约束。
     */
    public static boolean canPickup(String status) {
        return OrderTransition.PICKUP.allows(status);
    }

    /**
     * 状态中文名。同一个 ready 在配送单上叫「配送中」，所以要看取送方式。
     *
     * @param deliveryType pickup / delivery，null 按自取处理（老数据兜底）
     */
    public static String label(String status, String deliveryType) {
        if (READY.equals(status)) {
            return TYPE_DELIVERY.equals(deliveryType) ? "配送中" : "待取餐";
        }
        return label(status);
    }

    /** 与取送方式无关的状态中文名。ready 一律按自取口径，只在拿不到订单时用。 */
    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case PENDING_ACCEPT -> "待接单";
            case ACCEPTED -> "已接单";
            case READY -> "待取餐";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case REJECTED -> "已拒单";
            default -> status;
        };
    }
}

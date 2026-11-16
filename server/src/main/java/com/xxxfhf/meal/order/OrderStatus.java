package com.xxxfhf.meal.order;

import java.util.Set;

/**
 * 订单主状态与迁移规则。与需求 4.9 的状态机一一对应。
 *
 * 迁移规则写在这里而不是散落在各个 Service 里：状态机是这张表最硬的约束，
 * 判定散开之后，加一个状态就会出现「某条路径能进、另一条进不去」的分裂。
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    public static final String PENDING_ACCEPT = "pending_accept";
    public static final String ACCEPTED = "accepted";
    public static final String MAKING = "making";
    public static final String READY = "ready";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";
    public static final String REJECTED = "rejected";

    /**
     * 计入备菜的状态：**已接单及之后**。
     * 待接单不计入 —— 商家接完单后备菜数字才增加，这是需求第六章明确的口径。
     */
    public static final Set<String> PREP_INCLUDED =
            Set.of(ACCEPTED, MAKING, READY, COMPLETED);

    /** 终态：不再有任何流转。 */
    public static boolean isFinal(String status) {
        return COMPLETED.equals(status) || CANCELLED.equals(status) || REJECTED.equals(status);
    }

    /** 份数是否仍被这笔订单占着。取消 / 拒单后释放。 */
    public static boolean holdsStock(String status) {
        return !CANCELLED.equals(status) && !REJECTED.equals(status);
    }

    /** 商家可推进的目标状态白名单。不含接单与拒单（那两个有独立入口与额外校验）。 */
    public static boolean canAdvance(String from, String to) {
        return switch (from == null ? "" : from) {
            case ACCEPTED -> MAKING.equals(to);
            case MAKING -> READY.equals(to);
            case READY -> COMPLETED.equals(to);
            default -> false;
        };
    }

    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case PENDING_ACCEPT -> "待接单";
            case ACCEPTED -> "已接单";
            case MAKING -> "制作中";
            case READY -> "待取餐";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case REJECTED -> "已拒单";
            default -> status;
        };
    }
}

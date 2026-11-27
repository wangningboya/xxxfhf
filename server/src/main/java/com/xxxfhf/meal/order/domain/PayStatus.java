package com.xxxfhf.meal.order.domain;

/**
 * 收款状态。**与订单主状态独立**（需求 4.10）：饭做了没有、钱收没收到是两条线。
 *
 * 为什么要分成五种而不是「未收 / 已收」：
 * - `claimed` 是「用户说付了，我还没核对」——早上最需要和「根本没付」分开的两堆单
 * - `refunding` 是「已收款的单被拒/取消，钱还挂着」——月底对账时唯一能解释差额的状态
 */
public final class PayStatus {

    private PayStatus() {
    }

    public static final String UNPAID = "unpaid";
    public static final String CLAIMED = "claimed";
    public static final String PAID = "paid";
    public static final String REFUNDING = "refunding";
    public static final String REFUNDED = "refunded";

    /** 钱实际已到商家手里（含待退）的状态。超时释放不得动这些单。 */
    public static boolean isMoneyIn(String status) {
        return PAID.equals(status) || REFUNDING.equals(status) || REFUNDED.equals(status);
    }

    /**
     * 用户是否表达了「我付款了」。超时释放的判断依据：
     * 只要用户点过「我已完成付款」，份数就不释放，避免误伤已付款用户。
     */
    public static boolean userClaimed(String status) {
        return CLAIMED.equals(status) || isMoneyIn(status);
    }

    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case UNPAID -> "未收款";
            case CLAIMED -> "用户声称已付款";
            case PAID -> "已确认收款";
            case REFUNDING -> "待退款";
            case REFUNDED -> "已退款";
            default -> status;
        };
    }
}

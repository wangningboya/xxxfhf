package com.xxxfhf.meal.order;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单扫描口径：定时任务的扫描 SQL 与 Service 的条件更新共用这一份常量。
 *
 * <p>分开写的时候，改口径最容易只改一边：SQL 扫到了、Service 判定「不该处理」直接跳过，
 * 或者 Service 口径放宽了、SQL 却扫不到，单子从此没人管。两种都不报错，只在某天对账时发现。
 *
 * <p>这里只放「哪些单」的判定，不放「怎么处理」；处理逻辑仍在各自的 Service 里。
 *
 * <p>两组「可释放」口径都用**白名单**而不是排除法：将来新增一种状态，默认是不处理，
 * 而不是被当成「和以前一样」而误放。超时释放宁可少放，不可误伤已付款用户。
 */
public final class OrderQuery {

    private OrderQuery() {
    }

    /** 超时未付款可释放份数的订单状态：只有还停在待接单的（被接 / 被拒 / 被取消都不在列）。 */
    public static final Set<String> PREPAY_RELEASABLE_STATUSES = Set.of(OrderStatus.PENDING_ACCEPT);

    /** 超时未付款可释放份数的收款状态：只认「用户还没对这笔钱表过态」。 */
    public static final Set<String> PREPAY_RELEASABLE_PAY_STATUSES = Set.of(PayStatus.UNPAID);

    /**
     * 取消申请自动同意扫「已接单」——按需求 4.9 三档规则，申请只可能产生于
     * 「已接单 + 已过截单」（待接单随时可自行取消，不需要申请）。
     *
     * <p>仍保留待接单在集合里：v3.48 之前提交的老申请可能还挂在待接单上，
     * 不扫它们等于让这些单永久占着份数。新申请不会再落到这个状态。
     *
     * <p>比 {@code OrderTransition.CANCEL_APPROVED.fromStatuses()} 窄，是刻意的：后者是「人点了同意」
     * 时允许的源状态，前者是「系统替人同意」时敢碰的源状态。
     */
    public static final Set<String> CANCEL_SCAN_STATUSES = Set.of(OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED);

    /** 单次扫描上限：300 单/天的量级，200 条足够；设上限是防止某次积压把内存撑爆。 */
    public static final int SCAN_BATCH_LIMIT = 200;

    /**
     * 超时未付款扫描 SQL（供 JdbcTemplate）。
     *
     * <p>WHERE 与 {@link #prepayReleasable(String, String)} 一一对应：两处都只引上面的常量集合。
     */
    public static final String PREPAY_RELEASE_SCAN_SQL = """
            SELECT id FROM `order`
             WHERE status IN (%s)
               AND pay_status IN (%s)
               AND stock_released = 0
               AND created_at <= DATE_SUB(NOW(), INTERVAL ? MINUTE)
             ORDER BY created_at ASC
             LIMIT ?
            """.formatted(inList(PREPAY_RELEASABLE_STATUSES), inList(PREPAY_RELEASABLE_PAY_STATUSES));

    /** 取消申请超时扫描 SQL（供 JdbcTemplate）。 */
    public static final String CANCEL_REQUEST_SCAN_SQL = """
            SELECT id FROM `order`
             WHERE cancel_requested = 1
               AND status IN (%s)
               AND cancel_requested_at IS NOT NULL
               AND cancel_requested_at <= DATE_SUB(NOW(), INTERVAL ? HOUR)
             ORDER BY cancel_requested_at ASC
             LIMIT ?
            """.formatted(inList(CANCEL_SCAN_STATUSES));

    /**
     * Service 侧的同一条判定：这单还能不能释放份数。
     *
     * <p>与 {@link #PREPAY_RELEASE_SCAN_SQL} 一一对应：SQL 扫出来的单这里必然为 true，
     * 返回 false 只剩「并发下已经被别人改过」这一种情况。
     */
    public static boolean prepayReleasable(String status, String payStatus) {
        if (status == null || payStatus == null) {
            return false;
        }
        return PREPAY_RELEASABLE_STATUSES.contains(status) && PREPAY_RELEASABLE_PAY_STATUSES.contains(payStatus);
    }

    /**
     * 拼 SQL 的 IN 列表。值全部来自本类 / OrderStatus / PayStatus 的常量，不含任何外部输入。
     *
     * <p>排序是为了让生成的 SQL 文本稳定：{@code Set.of} 的迭代顺序不保证，同一份代码两次启动
     * 拼出不同顺序的 SQL 只会让日志比对和慢查询归并变麻烦。
     */
    private static String inList(Collection<String> values) {
        return values.stream().sorted().map(value -> "'" + value + "'").collect(Collectors.joining(", "));
    }
}

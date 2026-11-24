package com.xxxfhf.meal.task;

import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.notify.NotifyService;
import com.xxxfhf.meal.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 过期未接单自动关闭（v3.50 / 需求 4.9 / `F-E11`）。
 *
 * <p>扫描 {@code serve_date < 今天} 且仍是待接单的订单，逐单交给
 * {@link OrderService#expirePending(Long)}：转已取消、释放份数、已收钱转待退款、给用户发站内消息。
 *
 * <p>为什么是「供餐日次日」而不是「截单后」：截单在供餐日前一天，商家多半第二天早上才统一接单。
 * 截单一过就关，等于把当天所有订单都废掉 —— 这正是现有超时释放任务**只释放份数、不改状态**的原因。
 * 只有这一天过完，才算真的没人管。
 *
 * <p>逐单独立事务（由 {@code expirePending} 的 {@code @Transactional} 保证）：
 * 一单数据异常不该让后面几十单都关不掉。
 */
@Component
@ConditionalOnProperty(prefix = "app.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExpireOrderTask {

    private static final Logger log = LoggerFactory.getLogger(ExpireOrderTask.class);

    /** 一次最多处理多少单，避免历史脏数据一次扫出几万条 */
    private static final int BATCH_LIMIT = 500;

    private static final String SCAN_SQL = "SELECT id FROM `order` "
            + "WHERE status = 'pending_accept' AND serve_date < ? "
            + "ORDER BY serve_date ASC, id ASC LIMIT ?";

    private final JdbcTemplate jdbcTemplate;
    private final OrderService orderService;
    private final NotifyService notifyService;
    private final ServeDateClock clock;

    public ExpireOrderTask(JdbcTemplate jdbcTemplate, OrderService orderService,
                           NotifyService notifyService, ServeDateClock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderService = orderService;
        this.notifyService = notifyService;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.schedule.expire-order-cron}")
    public void scan() {
        try {
            LocalDate today = clock.today();
            List<Long> ids = jdbcTemplate.queryForList(SCAN_SQL, Long.class, today, BATCH_LIMIT);
            if (ids.isEmpty()) {
                log.debug("过期未接单扫描：无命中（today={}）", today);
                return;
            }

            int closed = 0;
            for (Long orderId : ids) {
                try {
                    if (orderService.expirePending(orderId)) {
                        closed++;
                    }
                } catch (Exception ex) {
                    log.error("关闭过期订单失败 orderId={}", orderId, ex);
                }
            }
            log.info("过期未接单扫描：命中 {} 单，实际关闭 {} 单", ids.size(), closed);
            // 商家只收一条汇总：早上不该被几十条「已自动关闭」刷屏
            notifyService.orderExpiredSummary(closed);
        } catch (Exception ex) {
            // 定时任务异常不能抛出去，否则调度线程会静默停止后续触发
            log.error("过期未接单扫描任务执行失败", ex);
        }
    }
}

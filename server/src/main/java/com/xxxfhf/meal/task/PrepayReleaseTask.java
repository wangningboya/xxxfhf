package com.xxxfhf.meal.task;

import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 超时未付款扫描（F-E06）。
 *
 * 动作只有两个：**释放份数**、置 `stock_released = 1`。订单状态**不变**，仍在待接单。
 *
 * 为什么不直接取消订单：截单在供餐日前一天，商家多半次日上午统一接单；
 * 把超时的单直接取消，等于把截单前下的单几乎全废掉（技术文档 4.4）。
 *
 * 三条不释放的红线：
 * - 已经不是待接单（被接 / 被拒 / 被取消）
 * - 用户点过「我已完成付款」（`claimed` 及以上）—— 宁可少释放，不可误伤已付款用户
 * - 已经释放过（`stock_released = 1`）
 */
@Component
@ConditionalOnProperty(prefix = "app.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PrepayReleaseTask {

    private static final Logger log = LoggerFactory.getLogger(PrepayReleaseTask.class);

    /** 单次扫描上限。300 单/天的量级，200 条足够；设上限是防止某次积压把内存撑爆。 */
    private static final int BATCH_LIMIT = 200;

    private static final String SCAN_SQL = """
            SELECT id FROM `order`
             WHERE status = 'pending_accept'
               AND pay_status = 'unpaid'
               AND stock_released = 0
               AND created_at <= DATE_SUB(NOW(), INTERVAL ? MINUTE)
             ORDER BY created_at ASC
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final OrderService orderService;

    public PrepayReleaseTask(JdbcTemplate jdbcTemplate, ConfigService configService,
                             OrderService orderService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.orderService = orderService;
    }

    @Scheduled(cron = "${app.schedule.prepay-release-cron}")
    public void scan() {
        try {
            // 分钟数读配置不写死：商家可能想把 30 分钟改成 20 分钟
            int minutes = Math.max(1, configService.getInt(ConfigKey.PREPAY_RELEASE_MINUTES, 30));
            List<Long> ids = jdbcTemplate.queryForList(SCAN_SQL, Long.class, minutes, BATCH_LIMIT);
            if (ids.isEmpty()) {
                log.debug("超时未付款扫描：无命中");
                return;
            }

            int released = 0;
            for (Long orderId : ids) {
                try {
                    if (orderService.releaseForTimeout(orderId)) {
                        released++;
                    }
                } catch (Exception ex) {
                    // 单笔失败不能中断整批：一单数据异常不该让后面几十单都释放不了
                    log.error("释放份数失败 orderId={}", orderId, ex);
                }
            }
            log.info("超时未付款扫描：命中 {} 单（门槛 {} 分钟），实际释放 {} 单",
                    ids.size(), minutes, released);
        } catch (Exception ex) {
            // 定时任务异常不能抛出去，否则调度线程会静默停止后续触发
            log.error("超时未付款扫描任务执行失败", ex);
        }
    }
}

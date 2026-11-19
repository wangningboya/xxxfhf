package com.xxxfhf.meal.task;

import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.order.OrderAdminService;
import com.xxxfhf.meal.order.OrderQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 取消申请超时自动同意（N-14）。
 *
 * 解决的问题：用户截单后提交退订申请，商家一直不处理，订单卡在待接单，
 * 份数一直被占 —— 这份盒饭再也卖不出去，用户也不知道到底批不批。
 *
 * 三条边界（口径见 {@link OrderQuery}，扫描 SQL 与 Service 的判定共用同一组常量）：
 * - 只扫 `pending_accept`：已接单的退订是另一回事（商家已经做了，必须人工判断）
 * - 只扫 `cancel_requested = 1`：没申请的单不能被这个任务碰
 * - 时长读配置，`0` 表示关闭自动同意（有的商家就是要自己逐单看）
 *
 * 与 PrepayReleaseTask 的区别：那个只释放份数、不改状态；这个真的把订单取消掉，
 * 因为用户明确表达了不要了，而商家用沉默表示了同意。
 */
@Component
@ConditionalOnProperty(prefix = "app.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CancelRequestTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(CancelRequestTimeoutTask.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final OrderAdminService orderAdminService;

    public CancelRequestTimeoutTask(JdbcTemplate jdbcTemplate, ConfigService configService,
                                    OrderAdminService orderAdminService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.orderAdminService = orderAdminService;
    }

    @Scheduled(cron = "${app.schedule.cancel-request-cron}")
    public void scan() {
        try {
            int hours = configService.getInt(ConfigKey.CANCEL_REQUEST_TIMEOUT_HOURS, 0);
            if (hours <= 0) {
                // 配置为 0 = 关闭自动同意，这条日志用 debug，免得每 10 分钟刷一次
                log.debug("取消申请自动同意已关闭（cancelRequestTimeoutHours={}）", hours);
                return;
            }
            List<Long> ids = jdbcTemplate.queryForList(OrderQuery.CANCEL_REQUEST_SCAN_SQL,
                    Long.class, hours, OrderQuery.SCAN_BATCH_LIMIT);
            if (ids.isEmpty()) {
                log.debug("取消申请超时扫描：无命中");
                return;
            }

            int approved = 0;
            for (Long orderId : ids) {
                try {
                    orderAdminService.autoApproveCancelRequest(orderId, hours);
                    approved++;
                } catch (Exception ex) {
                    // 单笔失败不能中断整批：商家可能恰好同时手动处理了这一单，属正常竞争
                    log.warn("自动同意取消失败 orderId={}", orderId, ex);
                }
            }
            log.info("取消申请超时扫描：命中 {} 单（门槛 {} 小时），自动同意 {} 单",
                    ids.size(), hours, approved);
        } catch (Exception ex) {
            // 定时任务异常不能抛出去，否则调度线程会静默停止后续触发
            log.error("取消申请超时扫描任务执行失败", ex);
        }
    }
}

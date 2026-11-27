package com.xxxfhf.meal.task;

import com.xxxfhf.meal.message.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 站内消息清理：删除 90 天前的消息。
 *
 * <p>为什么要有期限：{@code user_message} 只增不删会无限膨胀，而 90 天前的通知
 * 对任何人都不再有意义 —— 订单本身早就结束，点进去也是过期信息。
 *
 * <p><b>未读的一并删</b>：90 天没被点开的消息，留着也不会再点开；保留它只会让
 * 「未读数」这个指标失去意义。删完之后消息中心可能变空，这是预期行为。
 *
 * <p>分批删（每批 1000 条）而不是一条 DELETE 扫全表：消息量上来后一次性删除会长时间
 * 持有行锁与 undolog，把正常读写拖慢。
 *
 * <p>异常必须在任务内兜住：抛出去会让调度线程静默停止后续触发，
 * 表现是「任务只跑了一次就再也不跑了」。
 */
@Component
@ConditionalOnProperty(prefix = "app.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MessageCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(MessageCleanupTask.class);

    /** 保留天数 */
    private static final int KEEP_DAYS = 90;
    /** 每批删除条数 */
    private static final int BATCH_SIZE = 1000;

    private final MessageService messageService;

    public MessageCleanupTask(MessageService messageService) {
        this.messageService = messageService;
    }

    /** 默认每天 03:30，业务低谷；cron 走配置，便于临时调整而不用发版。 */
    @Scheduled(cron = "${app.schedule.message-cleanup-cron}")
    public void cleanup() {
        try {
            int deleted = messageService.cleanup(KEEP_DAYS, BATCH_SIZE);
            if (deleted > 0) {
                log.info("站内消息清理：删除 {} 条（早于 {} 天前）", deleted, KEEP_DAYS);
            }
        } catch (Exception ex) {
            log.error("站内消息清理任务执行失败", ex);
        }
    }
}

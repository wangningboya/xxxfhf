package com.xxxfhf.meal.task;

import com.xxxfhf.meal.invite.InviteCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 邀请码过期标记任务。
 *
 * 只是把「过了 expire_at 但状态还是 unused」的码刷成 expired，让后台列表看起来准确。
 * 核销判定**不依赖**这个状态：核销 SQL 自带 expire_at > NOW() 条件，
 * 所以即使本任务停掉，过期码也绝不可能被用掉。
 */
@Component
@ConditionalOnProperty(prefix = "app.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InviteExpireTask {

    private static final Logger log = LoggerFactory.getLogger(InviteExpireTask.class);

    private final InviteCodeService inviteCodeService;

    public InviteExpireTask(InviteCodeService inviteCodeService) {
        this.inviteCodeService = inviteCodeService;
    }

    /** 每 1 分钟一次。码量极小，扫描成本可忽略；列表查询前也会兜一次，不让商家看到「未使用」的过期码。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void markExpired() {
        try {
            int affected = inviteCodeService.markExpired();
            if (affected > 0) {
                log.info("邀请码过期标记：{} 条", affected);
            }
        } catch (Exception ex) {
            // 定时任务异常不能抛出去，否则调度线程会静默停止后续触发
            log.error("邀请码过期标记任务执行失败", ex);
        }
    }
}

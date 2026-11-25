package com.xxxfhf.meal.task;

import com.xxxfhf.meal.config.AppProperties;
import com.xxxfhf.meal.file.FileStorageService;
import com.xxxfhf.meal.print.QrCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 标签小程序码清理：删除超过保留期（默认 180 天）的 {@code qr/*.png}。
 *
 * <p>为什么需要期限：码按订单号一单一张，只增不删。单张只有几 KB，但文件数无上限，
 * 一年几千个，备份、迁移、翻目录都会变慢，而且没人会主动去看它。
 *
 * <p>为什么删了不心疼：码的内容只有订单号，{@code scene} 是确定值，重打这张单时
 * 后台会重新生成一模一样的码；唯一代价是那一张标签先走一次无码降级布局
 * （自取单放大取餐码，配送单右栏只剩份数），下一张就有码了。
 *
 * <p>失败只在任务内兜住：抛出去会让调度线程静默停止后续触发，
 * 表现是「任务只跑了一次就再也不跑了」。
 */
@Component
@ConditionalOnProperty(prefix = "app.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QrCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(QrCleanupTask.class);

    private final QrCodeService qrCodeService;
    private final AppProperties props;

    public QrCleanupTask(QrCodeService qrCodeService, AppProperties props) {
        this.qrCodeService = qrCodeService;
        this.props = props;
    }

    /** 默认每天 03:40，错开 03:30 的站内消息清理，业务低谷；cron 走配置，便于临时调整而不用发版。 */
    @Scheduled(cron = "${app.schedule.qr-cleanup-cron:0 40 3 * * *}")
    public void cleanup() {
        int keepDays = props.getQr().getKeepDays();
        try {
            FileStorageService.CleanResult result = qrCodeService.cleanup(keepDays);
            // 每天都记一条：删了 0 张也是信息，能证明任务还在跑
            log.info("小程序码清理：删除 {} 张（保留 {} 天），剩余 {} 张 / {} KB",
                    result.deleted(), keepDays, result.remaining(),
                    Math.round(result.remainingBytes() / 1024.0));
        } catch (Exception ex) {
            log.error("小程序码清理任务执行失败", ex);
        }
    }
}

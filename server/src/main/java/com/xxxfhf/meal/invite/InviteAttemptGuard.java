package com.xxxfhf.meal.invite;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邀请码错误次数限制：同一个微信账号 30 分钟内错 10 次，锁 30 分钟。
 *
 * <p>为什么不记数据库：这张表只为挡暴力试码，重启丢计数可以接受；
 * 记库反而要在每次核销失败时多写一行，而失败本来就是高频场景。
 *
 * <p>为什么按 openid 计数而不是 userId：首次进入走 /auth/join，核销失败时整个事务回滚，
 * 刚插入的用户行也不存在了，userId 下一次就换了；openid 才是稳定的那个人。
 *
 * <p>成功一次就清零：正常用户手滑错一两次，不该在半小时后还背着前一次的账。
 */
@Component
public class InviteAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(InviteAttemptGuard.class);

    /** 进程内计数不能无限涨：超过这个量就顺手清掉已过锁定期/从未锁定的记录 */
    private static final int MAX_TRACKED = 10_000;

    /**
     * 阈值与锁定时长走环境变量（APP_INVITE_LOCK_MAX_FAILURES / APP_INVITE_LOCK_MINUTES，
     * 默认 10 次 / 30 分钟）。这是防爆破的闸门：6 位纯数字码只有 100 万种组合，
     * 放宽阈值等于把爆破成本砍半，所以不给后台可调。
     */
    private final AppProperties appProperties;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public InviteAttemptGuard(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 提交核销前先看是否还在锁定期，锁着就直接拒绝，不查库也不烧码。
     *
     * @param key 微信 openid（拿不到时用 userId 兜底）
     */
    public void check(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (attempt.lockedUntil != null && now.isBefore(attempt.lockedUntil)) {
            long minutes = Math.max(1, Duration.between(now, attempt.lockedUntil).toMinutes() + 1);
            throw BizException.forbidden("邀请码错误次数过多，请 " + minutes + " 分钟后再试");
        }
    }

    /** 记录一次核销失败。到第 N 次（默认 10）失败的同时开始锁定 M 分钟（默认 30）。 */
    public void onFailure(String key) {
        int maxFailures = appProperties.getInvite().getLock().getMaxFailures();
        Duration lock = Duration.ofMinutes(appProperties.getInvite().getLock().getMinutes());
        LocalDateTime now = LocalDateTime.now();
        attempts.compute(key, (ignored, old) -> {
            Attempt attempt = old;
            // 锁已过期，或从没锁过：这两种情况都从这一次重新数
            if (attempt == null || (attempt.lockedUntil != null && !now.isBefore(attempt.lockedUntil))) {
                attempt = new Attempt();
            }
            attempt.failures++;
            if (attempt.failures >= maxFailures) {
                attempt.lockedUntil = now.plus(lock);
                log.warn("邀请码错误次数达到上限，锁定 {} 分钟 key={}", lock.toMinutes(), mask(key));
            }
            return attempt;
        });
        if (attempts.size() > MAX_TRACKED) {
            attempts.entrySet().removeIf(entry -> {
                Attempt attempt = entry.getValue();
                return attempt.lockedUntil == null || !now.isBefore(attempt.lockedUntil);
            });
        }
    }

    /** 核销成功：清零这个账号的失败计数。 */
    public void onSuccess(String key) {
        attempts.remove(key);
    }

    /** openid 是敏感值，日志只留前 6 位，够把同一账号的几条日志串起来即可。 */
    private static String mask(String key) {
        if (key == null || key.length() <= 6) {
            return "***";
        }
        return key.substring(0, 6) + "***";
    }

    private static final class Attempt {
        private int failures;
        private LocalDateTime lockedUntil;
    }
}

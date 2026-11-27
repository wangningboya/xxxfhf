package com.xxxfhf.meal.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 进程内滑动窗口限流：每个 key 记录窗口内每次请求发生的时刻，判定时先丢弃窗口外的，再看剩下几个。
 *
 * <p>为什么是滑动窗口而不是「计数 + 过期」：固定窗口在两个窗口的交界处最坏会双倍放行
 * （59 秒和 61 秒各打满一次，等于 2 倍阈值）。滑动窗口没有这道缝。
 *
 * <p>为什么不用 Redis：当前单实例，请求都进同一个 JVM，内存计数本身就是全局准确的，还少一次
 * 网络往返。代价是进程重启后配额清零、多实例各算各的——两条都记在 {@code 限流需求.md} 第九节，
 * 扩容前必须换成 Redis 或网关层限流。
 *
 * <p>线程安全：所有读写都在 {@link ConcurrentHashMap#compute} 的同一个 key 锁内完成，
 * 所以内部用非线程安全的 {@code ArrayDeque} 是安全的。切勿在外面先 get 再改——那样两个并发
 * 请求会同时看到「还差 1 个」然后都放行。
 */
public final class SlidingWindowLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowLimiter.class);

    /** 每 5 秒扫一次空队列：否则被扫过的 key 会永久占着内存 */
    private static final long SWEEP_INTERVAL_MILLIS = 5_000;

    /** key 数量兜底上限。正常流量远达不到；超过说明来源异常，重置比 OOM 好 */
    private static final int MAX_TRACKED_KEYS = 20_000;

    /**
     * 观察模式（dry-run）下队列最多记到阈值的 4 倍。
     *
     * <p>观察模式不拒绝请求，队列会一直涨，封个顶既能测到真实峰值，又不会被刷子把内存撑爆。
     */
    private static final int OBSERVE_FACTOR = 4;

    private final int limit;
    private final long windowMillis;
    private final LongSupplier nanoClock;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepAt = new AtomicLong();

    public SlidingWindowLimiter(int limit, int windowSeconds) {
        this(limit, windowSeconds, System::nanoTime);
    }

    /**
     * 供单测注入可控时钟。
     *
     * <p>生产用 {@link System#nanoTime()} 而不是 {@code currentTimeMillis}：后者会被 NTP 校时
     * 往回调，窗口会短暂变宽，放过几个本该拒绝的请求。nanoTime 只在进程内做差值，不受墙钟影响。
     */
    SlidingWindowLimiter(int limit, int windowSeconds, LongSupplier nanoClock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须 ≥ 1，当前 " + limit);
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds 必须 ≥ 1，当前 " + windowSeconds);
        }
        this.limit = limit;
        this.windowMillis = windowSeconds * 1000L;
        this.nanoClock = nanoClock;
    }

    /**
     * 记一次请求并判定。
     *
     * <p>本方法只负责计数和判定，<b>不决定拦不拦</b>：拦不拦由调用方根据 {@link Decision#overLimit()}
     * 与自身的 dry-run 开关决定。这样同一个算法既能用于观察模式，也能用于强制拦截。
     *
     * @param key 配额归属，如 {@code u:123}、{@code ip:1.2.3.4}
     */
    public Decision acquire(String key) {
        long now = nowMillis();
        // compute 的 lambda 不能写外部变量，用单元素数组回传结果
        boolean[] overLimit = {false};
        int[] count = {0};
        long[] retryAfter = {0};

        windows.compute(key, (k, existing) -> {
            Deque<Long> queue = existing == null ? new ArrayDeque<>(Math.min(limit + 1, 64)) : existing;

            // ① 丢掉窗口左边界外的（sliding 的关键：留在队列里的永远只是最近一个窗口内的请求）
            while (!queue.isEmpty() && queue.peekFirst() <= now - windowMillis) {
                queue.pollFirst();
            }

            // ② 判定：本次是第 limit+1 个才算超限（阈值 60 表示 60 次放行，第 61 次开始拒）
            overLimit[0] = queue.size() >= limit;

            // ③ 记账。超限也继续记：观察模式要靠它统计真实峰值；封顶 4 倍防止被刷爆内存
            if (queue.size() < (long) limit * OBSERVE_FACTOR) {
                queue.addLast(now);
            }

            count[0] = queue.size();

            // 超限时算「最早那次请求什么时候滑出窗口」，即还要等多久才有额度
            if (overLimit[0] && !queue.isEmpty()) {
                long waitMillis = queue.peekFirst() + windowMillis - now;
                retryAfter[0] = Math.max(1, (waitMillis + 999) / 1000);
            }
            return queue;
        });

        sweepIfDue(now);
        return new Decision(count[0], overLimit[0], retryAfter[0]);
    }

    /** 当前跟踪的 key 数量，仅用于单测验证清理是否生效。 */
    int trackedKeys() {
        return windows.size();
    }

    /**
     * 立即清理一次空闲 key，不节流。
     *
     * <p>给定时任务用：{@link #acquire} 里的清理只在「有新请求」时触发，长时间空转（比如深夜）
     * 就永远不会执行，窗口表会一直挂着最后一批 key。
     */
    public void sweep() {
        long now = nowMillis();
        lastSweepAt.set(now);
        sweepNow(now);
    }

    /**
     * 定期清理已经空了的 key。
     *
     * <p>必须走 {@code computeIfPresent}：直接 {@code entrySet().removeIf()} 会和并发的
     * {@code compute} 抢同一个 key，可能删掉刚被写入的队列，表现为「偶尔少记一次配额」。
     */
    private void sweepIfDue(long now) {
        long last = lastSweepAt.get();
        if (now - last < SWEEP_INTERVAL_MILLIS || !lastSweepAt.compareAndSet(last, now)) {
            return;
        }
        sweepNow(now);
    }

    private void sweepNow(long now) {
        for (String key : windows.keySet()) {
            windows.computeIfPresent(key, (k, queue) -> {
                while (!queue.isEmpty() && queue.peekFirst() <= now - windowMillis) {
                    queue.pollFirst();
                }
                return queue.isEmpty() ? null : queue;
            });
        }

        if (windows.size() > MAX_TRACKED_KEYS) {
            log.warn("限流窗口 key 数 {} 超过上限 {}，整体重置（正常流量不会触发，出现请查上面的 key 来源）",
                    windows.size(), MAX_TRACKED_KEYS);
            windows.clear();
        }
    }

    private long nowMillis() {
        // nanoTime 的起点是任意的、可能为负，用 floorDiv 而不是整数除法（负数除法会向 0 截断）
        return Math.floorDiv(nanoClock.getAsLong(), 1_000_000L);
    }

    /**
     * 一次判定的结果。
     *
     * @param count             本次记账后，窗口内的请求数（含本次）
     * @param overLimit         本次是否已超出阈值
     * @param retryAfterSeconds 超限时还需等待的秒数（从最早一次请求滑出窗口算）；未超限为 0
     */
    public record Decision(int count, boolean overLimit, long retryAfterSeconds) {
    }
}

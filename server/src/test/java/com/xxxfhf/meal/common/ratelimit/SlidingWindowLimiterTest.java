package com.xxxfhf.meal.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 滑动窗口限流：阈值内放行、超了拒绝、旧请求滑出窗口后恢复。
 *
 * <p>时钟是注入的（{@link AtomicLong} 模拟纳秒），所以"过了一分钟"不需要真的等一分钟，
 * 也不依赖系统墙钟。
 */
class SlidingWindowLimiterTest {

    /** 模拟时钟，单位纳秒 */
    private final AtomicLong nanos = new AtomicLong(0);

    private SlidingWindowLimiter limiter(int limit, int windowSeconds) {
        return new SlidingWindowLimiter(limit, windowSeconds, nanos::get);
    }

    private void advanceSeconds(long seconds) {
        nanos.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    @DisplayName("阈值 3：前 3 次放行，第 4 次开始超限")
    void rejectsAfterLimit() {
        SlidingWindowLimiter limiter = limiter(3, 60);

        for (int i = 1; i <= 3; i++) {
            SlidingWindowLimiter.Decision d = limiter.acquire("u:1");
            assertThat(d.overLimit()).as("第 %d 次不应超限", i).isFalse();
            assertThat(d.count()).isEqualTo(i);
        }

        SlidingWindowLimiter.Decision fourth = limiter.acquire("u:1");
        assertThat(fourth.overLimit()).isTrue();
        assertThat(fourth.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("超限时返回的等待秒数在窗口内")
    void retryAfterWithinWindow() {
        SlidingWindowLimiter limiter = limiter(2, 60);
        limiter.acquire("u:1");
        limiter.acquire("u:1");

        // 第 30 秒来第三次，最早那次还要 30 秒才滑出窗口
        advanceSeconds(30);
        SlidingWindowLimiter.Decision d = limiter.acquire("u:1");

        assertThat(d.overLimit()).isTrue();
        assertThat(d.retryAfterSeconds()).isBetween(29L, 31L);
    }

    @Test
    @DisplayName("窗口滑过后恢复：旧请求被丢弃，配额重新可用")
    void recoversAfterWindowSlides() {
        SlidingWindowLimiter limiter = limiter(2, 60);
        limiter.acquire("u:1");
        limiter.acquire("u:1");
        assertThat(limiter.acquire("u:1").overLimit()).isTrue();

        advanceSeconds(61);

        SlidingWindowLimiter.Decision after = limiter.acquire("u:1");
        assertThat(after.overLimit()).isFalse();
        assertThat(after.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 key 各算各的：一个人打满不影响别人")
    void keysAreIsolated() {
        SlidingWindowLimiter limiter = limiter(2, 60);
        limiter.acquire("u:1");
        limiter.acquire("u:1");
        assertThat(limiter.acquire("u:1").overLimit()).isTrue();

        assertThat(limiter.acquire("u:2").overLimit()).isFalse();
    }

    @Test
    @DisplayName("观察模式（不拒绝）下计数继续增长，但封顶在阈值的 4 倍")
    void observationCountIsCapped() {
        SlidingWindowLimiter limiter = limiter(2, 60);

        SlidingWindowLimiter.Decision last = null;
        for (int i = 0; i < 20; i++) {
            last = limiter.acquire("u:1");
        }

        assertThat(last).isNotNull();
        assertThat(last.overLimit()).isTrue();
        // 2 × 4 = 8：封顶是为了既能看到真实峰值，又不被刷子把内存撑爆
        assertThat(last.count()).isEqualTo(8);
    }

    @Test
    @DisplayName("空 key 会被清理，不会永久占用内存")
    void sweepRemovesIdleKeys() {
        SlidingWindowLimiter limiter = limiter(5, 60);
        limiter.acquire("u:1");
        limiter.acquire("u:2");
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        // 过窗口后再来一个请求，顺带触发清理；u:1 与 u:2 的队列已空，应被删掉
        advanceSeconds(61);
        limiter.acquire("u:3");

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("阈值或窗口非法时构造直接失败")
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new SlidingWindowLimiter(0, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> new SlidingWindowLimiter(60, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSeconds");
    }

    @Test
    @DisplayName("空转时也能清理：定时任务直接调 sweep()")
    void sweepWorksWithoutTraffic() {
        SlidingWindowLimiter limiter = limiter(5, 60);
        limiter.acquire("u:1");
        assertThat(limiter.trackedKeys()).isEqualTo(1);

        // 之后没有任何请求，只有定时清理节拍
        advanceSeconds(61);
        limiter.sweep();

        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    @DisplayName("并发打同一个 key：放行数恰好等于阈值，一次都不超发")
    void concurrentAcquireNeverExceedsLimit() throws Exception {
        int limit = 60;
        SlidingWindowLimiter limiter = limiter(limit, 60);
        int threads = 16;
        int perThread = 20;   // 合计 320 次，远超阈值，必然有大量请求同时抢最后几个名额

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            if (!limiter.acquire("u:1").overLimit()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).as("并发用例不应超时").isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 判定与写入必须在同一个 compute 里原子完成；漏锁会出现 61 次甚至更多
        assertThat(allowed.get()).isEqualTo(limit);
    }
}

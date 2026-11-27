package com.xxxfhf.meal.common.ratelimit;

import com.xxxfhf.meal.auth.service.JwtService;
import com.xxxfhf.meal.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitFilter 的行为测试：放行、拦截、豁免、配额归属。
 *
 * <p>不启动 Spring 上下文，直接 new 出 Filter 调 {@code doFilter}。这样测的是真实的
 * 拦截路径（OncePerRequestFilter → shouldNotFilter → doFilterInternal），又不依赖数据库与
 * 容器；{@link SlidingWindowLimiter} 的算法细节另由 {@code SlidingWindowLimiterTest} 覆盖。
 *
 * <p>每个用例都用小阈值（1~2）而不是配置里的 60，避免测试里循环几十次。
 */
class RateLimitFilterTest {

    /** HS256 要求至少 32 字节 */
    private static final String JWT_SECRET = "unit-test-secret-0123456789abcdef0123456789";

    private static final String IP = "10.0.0.1";

    // ---------------------------------------------------------------- 放行与拦截

    @Test
    @DisplayName("额度内放行，链路继续执行")
    void allowsUnderLimit() throws Exception {
        Result result = call(newFilter(props(2, 2, false), jwt()), request("POST", "/order/submit"));

        assertThat(result.reachedChain()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("超出额度返回 429，链路被截断，响应体是统一文案")
    void blocksOverLimitWith429() throws Exception {
        AppProperties props = props(2, 2, false);
        RateLimitFilter filter = newFilter(props, jwt());

        call(filter, request("POST", "/order/submit"));
        call(filter, request("POST", "/order/submit"));
        Result third = call(filter, request("POST", "/order/submit"));

        assertThat(third.reachedChain()).isFalse();
        assertThat(third.response().getStatus()).isEqualTo(429);
        String body = third.response().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("42901").contains("请求过快，请刷新后再试");
        assertThat(third.response().getContentType()).contains("application/json");
    }

    @Test
    @DisplayName("观察模式超限只记账，仍然放行")
    void dryRunPassesEvenWhenOverLimit() throws Exception {
        RateLimitFilter filter = newFilter(props(1, 1, true), jwt());

        call(filter, request("GET", "/menu"));
        Result second = call(filter, request("GET", "/menu"));

        assertThat(second.reachedChain()).isTrue();
        assertThat(second.response().getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- 配额归属

    @Test
    @DisplayName("登录入口走 L2 严格档：第 3 次就打满（阈值 2）")
    void loginEntryUsesStricterQuota() throws Exception {
        RateLimitFilter filter = newFilter(props(100, 2, false), jwt());

        assertThat(call(filter, request("POST", "/auth/login")).reachedChain()).isTrue();
        assertThat(call(filter, request("POST", "/auth/login")).reachedChain()).isTrue();
        Result third = call(filter, request("POST", "/auth/login"));

        assertThat(third.reachedChain()).isFalse();
        assertThat(third.response().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("/auth/admin/login 与小程序登录同档（登录档只剩这两条路径）")
    void adminLoginIsStrictToo() throws Exception {
        for (String path : new String[]{"/auth/login", "/auth/admin/login"}) {
            RateLimitFilter filter = newFilter(props(100, 1, false), jwt());
            assertThat(call(filter, request("POST", path)).reachedChain()).as(path).isTrue();
            assertThat(call(filter, request("POST", path)).reachedChain()).as(path).isFalse();
        }
    }

    @Test
    @DisplayName("/auth/join 走独立档：额度按其自身阈值计，不打满登录档")
    void joinEntryUsesOwnQuota() throws Exception {
        RateLimitFilter filter = newFilter(props(100, 1, 3, false), jwt());

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, request("POST", "/auth/join")).reachedChain()).as("第 %s 次", i + 1).isTrue();
        }
        Result fourth = call(filter, request("POST", "/auth/join"));
        assertThat(fourth.reachedChain()).isFalse();
        assertThat(fourth.response().getStatus()).isEqualTo(429);

        // 若两档共用计数，上面 3 次 join 早就把 loginLimit=1 打满了
        assertThat(call(filter, request("POST", "/auth/login")).reachedChain()).isTrue();
    }

    @Test
    @DisplayName("爆破：同一 IP 并发 100 次 /auth/join，进入业务的严格等于 join 额度")
    void concurrentBruteForceOnJoinIsCapped() throws Exception {
        int quota = 30;
        RateLimitFilter filter = newFilter(props(100, 10, quota, false), jwt());

        // 全部线程卡在同一个起跑线上，模拟「一个脚本瞬间打出 100 个请求」
        int threads = 100;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call(filter, request("POST", "/auth/join")).reachedChain();
                }));
            }
            start.countDown();

            int passed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    passed++;
                }
            }

            // 关键断言：不是 100，也不是「差不多」——SlidingWindowLimiter 在 compute 里判定，
            // 这个数是确定的。它正是「一个人快速并发试」被压回单倍的那道闸。
            assertThat(passed).isEqualTo(quota);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("L2 与 L1 不叠加：打满登录配额不影响普通接口")
    void loginQuotaDoesNotConsumeGlobalQuota() throws Exception {
        RateLimitFilter filter = newFilter(props(2, 2, false), jwt());

        call(filter, request("POST", "/auth/login"));
        call(filter, request("POST", "/auth/login"));
        assertThat(call(filter, request("POST", "/auth/login")).response().getStatus()).isEqualTo(429);

        // 若两侧共用计数，这里会是第 3 次 → 429
        Result normal = call(filter, request("POST", "/order/submit"));
        assertThat(normal.reachedChain()).isTrue();
    }

    @Test
    @DisplayName("普通接口按 userId 计数，同一 IP 的不同用户互不影响")
    void countsByUserIdNotByIp() throws Exception {
        RateLimitFilter filter = newFilter(props(1, 1, false), jwt());

        assertThat(call(filter, authed("/order/submit", 1L)).reachedChain()).isTrue();
        assertThat(call(filter, authed("/order/submit", 1L)).reachedChain()).isFalse();

        // 同一来源 IP、不同用户：如果按 IP 计数会被上一条连累
        assertThat(call(filter, authed("/order/submit", 2L)).reachedChain()).isTrue();
    }

    @Test
    @DisplayName("同一用户在换 IP 后仍共用一份配额")
    void sameUserSharesQuotaAcrossIps() throws Exception {
        RateLimitFilter filter = newFilter(props(1, 1, false), jwt());

        MockHttpServletRequest first = authed("/order/submit", 7L);
        first.addHeader("X-Real-IP", "1.1.1.1");
        assertThat(call(filter, first).reachedChain()).isTrue();

        MockHttpServletRequest second = authed("/order/submit", 7L);
        second.addHeader("X-Real-IP", "2.2.2.2");
        assertThat(call(filter, second).reachedChain()).isFalse();
    }

    @Test
    @DisplayName("没有令牌时按 IP，且优先取 X-Real-IP 而不是 remoteAddr")
    void anonymousFallsBackToRealIpHeader() throws Exception {
        RateLimitFilter filter = newFilter(props(1, 1, false), jwt());

        // 同一个 remoteAddr（生产里恒为 127.0.0.1），但 X-Real-IP 不同 → 应各自独立
        MockHttpServletRequest a = request("GET", "/menu");
        a.addHeader("X-Real-IP", "1.1.1.1");
        assertThat(call(filter, a).reachedChain()).isTrue();

        MockHttpServletRequest b = request("GET", "/menu");
        b.addHeader("X-Real-IP", "2.2.2.2");
        assertThat(call(filter, b).reachedChain()).isTrue();

        // 回到第一个 IP，配额已用完
        MockHttpServletRequest c = request("GET", "/menu");
        c.addHeader("X-Real-IP", "1.1.1.1");
        assertThat(call(filter, c).reachedChain()).isFalse();
    }

    @Test
    @DisplayName("没有 X-Real-IP 时退回 remoteAddr")
    void anonymousFallsBackToRemoteAddr() throws Exception {
        RateLimitFilter filter = newFilter(props(1, 1, false), jwt());

        assertThat(call(filter, request("GET", "/menu")).reachedChain()).isTrue();
        assertThat(call(filter, request("GET", "/menu")).reachedChain()).isFalse();
    }

    @Test
    @DisplayName("令牌乱码或已过期：回退按 IP 计数，不抛异常、不误当作新用户")
    void invalidOrExpiredTokenFallsBackToIp() throws Exception {
        RateLimitFilter filter = newFilter(props(1, 1, false), jwt());

        // 乱码令牌 —— 客户端被改、或前端把非 JWT 的东西塞进了 Authorization
        MockHttpServletRequest garbage = request("GET", "/menu");
        garbage.addHeader("Authorization", "Bearer not.a.real.token");
        assertThat(call(filter, garbage).reachedChain()).isTrue();

        // 签名合法但已过期 —— 7 天不动小程序、token 自然过期的真实场景。
        // 若这里被当成独立用户，会绕过按 IP 的兜底；若 parse 抛异常，会变成 500。
        MockHttpServletRequest expired = request("GET", "/menu");
        expired.addHeader("Authorization", "Bearer " + expiredToken(9L));
        Result second = call(filter, expired);
        assertThat(second.response().getStatus()).isEqualTo(429);
        assertThat(second.reachedChain()).isFalse();

        // 有效令牌是另一个桶（u:9），不被上面占用的 IP 配额连累
        MockHttpServletRequest valid = request("GET", "/menu");
        valid.addHeader("Authorization", "Bearer " + jwt().issue(9L, "customer", 0));
        assertThat(call(filter, valid).reachedChain()).isTrue();
    }

    // ---------------------------------------------------------------- 豁免

    @Test
    @DisplayName("OPTIONS 预检、健康检查、favicon、上传静态资源都不计数")
    void exemptsPreflightAndInfrastructurePaths() throws Exception {
        RateLimitFilter filter = newFilter(props(1, 1, false), jwt());

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, request("OPTIONS", "/order/submit")).reachedChain()).as("OPTIONS").isTrue();
            assertThat(call(filter, request("GET", "/health")).reachedChain()).as("/health").isTrue();
            assertThat(call(filter, request("GET", "/ready")).reachedChain()).as("/ready").isTrue();
            assertThat(call(filter, request("GET", "/favicon.ico")).reachedChain()).as("/favicon").isTrue();
            assertThat(call(filter, request("GET", "/upload/a.png")).reachedChain()).as("/upload").isTrue();
        }

        // 豁免路径没有占用配额：普通接口的第一次仍然放行
        assertThat(call(filter, request("GET", "/menu")).reachedChain()).isTrue();
    }

    // ---------------------------------------------------------------- 辅助

    private static RateLimitFilter newFilter(AppProperties props, JwtService jwt) {
        return new RateLimitFilter(props, jwt, new ObjectMapper());
    }

    private static AppProperties props(int limit, int loginLimit, boolean dryRun) {
        // 不关心 join 档的用例让它与全站额度一致；要断言 join 行为的用例走四参版本
        return props(limit, loginLimit, limit, dryRun);
    }

    private static AppProperties props(int limit, int loginLimit, int joinLimit, boolean dryRun) {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(JWT_SECRET);
        props.getRateLimit().setLimit(limit);
        props.getRateLimit().setLoginLimit(loginLimit);
        props.getRateLimit().setJoinLimit(joinLimit);
        props.getRateLimit().setWindowSeconds(60);
        props.getRateLimit().setDryRun(dryRun);
        return props;
    }

    private static JwtService jwt() {
        return new JwtService(props(60, 10, false));
    }

    /** 签发一个「签名合法但已过期」的令牌：把有效期设成负数，ttl 为负即天生过期 */
    private static String expiredToken(Long userId) {
        AppProperties expiredProps = props(60, 10, false);
        expiredProps.getJwt().setExpireDays(-1);
        return new JwtService(expiredProps).issue(userId, "customer", 0);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        request.setRemoteAddr(IP);
        return request;
    }

    private static MockHttpServletRequest authed(String uri, Long userId) {
        MockHttpServletRequest request = request("POST", uri);
        request.addHeader("Authorization", "Bearer " + jwt().issue(userId, "customer", 0));
        return request;
    }

    /** 执行一次过滤，记录链路是否被放行 */
    private static Result call(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedChain = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> reachedChain.set(true));
        return new Result(response, reachedChain.get());
    }

    private record Result(MockHttpServletResponse response, boolean reachedChain) {
    }
}

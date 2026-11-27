package com.xxxfhf.meal.common.ratelimit;

import com.xxxfhf.meal.auth.JwtService;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.ErrorCode;
import com.xxxfhf.meal.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用层限流：补 nginx 做不到的「按人」这一维。
 *
 * <p>为什么放在 Filter 而不是 {@code AuthInterceptor}：
 * <ul>
 *   <li>拦截器的白名单把 {@code /auth/login}、{@code /auth/join} 全排除了，而这两个恰恰最需要限流；</li>
 *   <li>Filter 在鉴权之前执行，被限流挡掉的请求不会再去查库，攻击流量不消耗数据库连接。</li>
 * </ul>
 *
 * <p>与 nginx 的分工：nginx 已按来源 IP 做了粗粒度限流（30r/s，见 nginx.conf），
 * 这里只补「同一个人」的维度。两层叠加，nginx 挡工具，这里挡慢慢刷的人。
 *
 * <p>配额归属：
 * <ul>
 *   <li>三个免登录入口按 IP —— 它们绕过了身份，脚本换 IP 就能无限刷，而 {@code /auth/join} 会写库；</li>
 *   <li>其余接口按 userId —— 群成员常连同一个 WiFi，出口 IP 相同，按 IP 会互相踩配额；</li>
 *   <li>取不到 userId（没令牌）也按 IP —— 这类请求马上会被拦截器 401，但仍占一份配额，
 *       避免「不带令牌」变成绕过限流的口子。</li>
 * </ul>
 *
 * <p>为什么两者不叠加：{@code /auth/login} 命中 L2 后直接返回，不再走 L1，否则一次登录要扣两次配额。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    /** 与 WebMvcConfig 的登录白名单保持一致：健康检查、错误转发、静态资源不限流 */
    private static final Set<String> EXEMPT_PATHS = Set.of("/health", "/ready", "/error");
    private static final String UPLOAD_PREFIX = "/upload/";
    /** 浏览器自动请求，限它没有意义 */
    private static final String FAVICON_PATH = "/favicon.ico";

    /**
     * 免登录入口（L2 严格档）。
     *
     * <p>必须与 WebMvcConfig.WHITE_LIST 里的三条一致：这是唯一「不带身份就能打、而且会写库或
     * 消耗微信接口配额」的入口。
     */
    private static final Set<String> LOGIN_PATHS = Set.of("/auth/login", "/auth/join", "/auth/admin/login");

    /** 日志去重表的兜底上限，正常流量远达不到 */
    private static final int MAX_LOG_MARKS = 5_000;

    private final AppProperties.RateLimit config;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final SlidingWindowLimiter globalLimiter;
    private final SlidingWindowLimiter loginLimiter;

    /** key -> 上次写日志的分钟号，用来实现「每人每分钟最多一条」，避免被刷时日志把磁盘写满 */
    private final Map<String, Long> lastLogMinute = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties appProperties, JwtService jwtService, ObjectMapper objectMapper) {
        this.config = appProperties.getRateLimit();
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.globalLimiter = new SlidingWindowLimiter(config.getLimit(), config.getWindowSeconds());
        this.loginLimiter = new SlidingWindowLimiter(config.getLoginLimit(), config.getWindowSeconds());
        log.info("应用层限流已装配：{} 模式，全站 {}/{}s，免登录入口 {}/{}s",
                config.isDryRun() ? "观察（只记日志不拦截）" : "强制拦截",
                config.getLimit(), config.getWindowSeconds(),
                config.getLoginLimit(), config.getWindowSeconds());
    }

    /**
     * 放行：健康检查、上传静态资源、favicon，以及所有 OPTIONS 预检。
     *
     * <p>OPTIONS 必须放行：网页后台跨域时会常发预检，把预检限掉会让后台出现
     * 「某些请求莫名 429、刷新又好了」这种极难查的现象。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return EXEMPT_PATHS.contains(path) || path.startsWith(UPLOAD_PREFIX) || FAVICON_PATH.equals(path);
    }

    /**
     * 空转时也要清理空闲 key。
     *
     * <p>{@link SlidingWindowLimiter#acquire} 里的清理只在「有新请求」时触发，深夜没人下单时
     * 窗口表会一直挂着最后一批 key。这里补一个独立的清理节拍，保证空转几分钟后能回落。
     */
    @Scheduled(fixedDelay = 5_000L)
    void sweepIdleKeys() {
        globalLimiter.sweep();
        loginLimiter.sweep();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean loginEntry = LOGIN_PATHS.contains(path);

        SlidingWindowLimiter limiter = loginEntry ? loginLimiter : globalLimiter;
        String key = loginEntry ? ipKey(request) : quotaKey(request);

        SlidingWindowLimiter.Decision decision = limiter.acquire(key);

        if (!decision.overLimit()) {
            chain.doFilter(request, response);
            return;
        }

        // 超限：先记一笔，再由 dry-run 决定是放行还是返回 429
        logIfDue(key, request, decision, loginEntry);
        if (config.isDryRun()) {
            chain.doFilter(request, response);
            return;
        }
        writeTooMany(response);
    }

    /**
     * 普通接口的配额归属：优先按人，取不到身份才按 IP。
     *
     * <p>这里只验签不查库（{@link JwtService#parse} 是纯本地计算），所以不会给每个请求加一次 SQL。
     * 令牌是否仍然有效、账号是否被禁用，仍由 AuthInterceptor 判断，限流只负责「配额用完没有」。
     */
    private String quotaKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                var payload = jwtService.parse(token);
                if (payload.isPresent()) {
                    return "u:" + payload.get().userId();
                }
            }
        }
        return ipKey(request);
    }

    private static String ipKey(HttpServletRequest request) {
        // 生产由 nginx 反代，getRemoteAddr() 恒为 127.0.0.1，必须取 nginx 覆盖写入的 X-Real-IP。
        // 不用 X-Forwarded-For：它是追加语义（$proxy_add_x_forwarded_for），客户端自带的值保留在
        // 最左段，取第一段等于让客户端自己决定 key。
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return "ip:" + realIp.trim();
        }
        // dev 直连 8080 时没有这个头，退回真实来源地址
        return "ip:" + request.getRemoteAddr();
    }

    /**
     * 超限日志，每个 key 每分钟最多一条。
     *
     * <p>观察期日志是唯一的调参依据，但被脚本刷时逐条打会把磁盘写满，所以按 key 每分钟汇总一次：
     * 记下本分钟第一次超限的接口和当时的窗口计数。
     */
    private void logIfDue(String key, HttpServletRequest request, SlidingWindowLimiter.Decision decision,
                          boolean loginEntry) {
        long minute = System.currentTimeMillis() / 60_000L;
        AtomicBoolean shouldLog = new AtomicBoolean(false);
        lastLogMinute.compute(key, (k, last) -> {
            if (last == null || last != minute) {
                shouldLog.set(true);
                return minute;
            }
            return last;
        });
        if (!shouldLog.get()) {
            return;
        }

        if (config.isDryRun()) {
            log.warn("限流观察：本应拦截 key={} 窗口内 {} 次（阈值 {}/{}s，{}）{} {}",
                    key, decision.count(), limitOf(loginEntry), config.getWindowSeconds(),
                    loginEntry ? "免登录入口" : "登录用户", request.getMethod(), request.getRequestURI());
        } else {
            log.warn("限流拦截 key={} 窗口内 {} 次（阈值 {}/{}s，{}）{} {} 需等待 {}s",
                    key, decision.count(), limitOf(loginEntry), config.getWindowSeconds(),
                    loginEntry ? "免登录入口" : "登录用户", request.getMethod(), request.getRequestURI(),
                    decision.retryAfterSeconds());
        }

        // 去重表兜底清理：正常情况下 key 数跟活跃用户数同量级
        if (lastLogMinute.size() > MAX_LOG_MARKS) {
            lastLogMinute.entrySet().removeIf(entry -> entry.getValue() < minute - 1);
        }
    }

    private int limitOf(boolean loginEntry) {
        return loginEntry ? config.getLoginLimit() : config.getLimit();
    }

    /**
     * 429 响应体必须自己写。
     *
     * <p>{@code @RestControllerAdvice} 只处理 Handler 抛出的异常，Filter 在 DispatcherServlet 之外，
     * 在这里抛异常不会进 GlobalExceptionHandler，只会变成容器的错误页。
     */
    private void writeTooMany(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.TOO_MANY_ATTEMPTS.httpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        // 与 nginx 那层的 429 文案一致；剩余秒数不返回，小程序没有对应的倒计时 UI
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.fail(ErrorCode.TOO_MANY_ATTEMPTS, null)));
    }
}

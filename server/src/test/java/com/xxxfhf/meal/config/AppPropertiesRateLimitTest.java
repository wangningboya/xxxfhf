package com.xxxfhf.meal.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流相关配置的启动校验。
 *
 * <p>这几条断言的意义在于「配错了就别启动」：限流阈值写错不会让程序报错，
 * 只会静默地不生效（比如免登录额度大于登录额度，等于那三个入口完全没限）。
 * 校验本身在 {@code @PostConstruct} 里，早于任何请求。
 */
class AppPropertiesRateLimitTest {

    private static final String JWT_SECRET = "unit-test-secret-0123456789abcdef0123456789";

    @Test
    @DisplayName("默认配置合法：60 次/60 秒，登录 10 次，首次登记 30 次，默认观察模式")
    void defaultsAreValid() {
        AppProperties props = valid();

        assertThatCode(props::validate).doesNotThrowAnyException();
        assertThat(props.getRateLimit().isDryRun()).isTrue();
        assertThat(props.getRateLimit().getLimit()).isEqualTo(60);
        assertThat(props.getRateLimit().getLoginLimit()).isEqualTo(10);
        assertThat(props.getRateLimit().getJoinLimit()).isEqualTo(30);
        assertThat(props.getRateLimit().getWindowSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("阈值 < 1：启动失败，而不是静默全拦")
    void rejectsNonPositiveLimit() {
        AppProperties props = valid();
        props.getRateLimit().setLimit(0);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("限流阈值必须 ≥ 1");
    }

    @Test
    @DisplayName("窗口 < 1：启动失败（0 秒窗口会让每个请求都被判超限）")
    void rejectsNonPositiveWindow() {
        AppProperties props = valid();
        props.getRateLimit().setWindowSeconds(0);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RATE_LIMIT_WINDOW_SECONDS 必须 ≥ 1");
    }

    @Test
    @DisplayName("免登录额度大于登录额度：启动失败，否则这两个写库入口等于没限")
    void rejectsLoginLimitGreaterThanLimit() {
        AppProperties props = valid();
        props.getRateLimit().setLimit(10);
        props.getRateLimit().setLoginLimit(60);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RATE_LIMIT_LOGIN_LIMIT 不能大于");
    }

    @Test
    @DisplayName("首次登记额度 < 1：启动失败（0 会让所有新用户都进不来）")
    void rejectsNonPositiveJoinLimit() {
        AppProperties props = valid();
        props.getRateLimit().setJoinLimit(0);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RATE_LIMIT_JOIN_LIMIT 必须 ≥ 1");
    }

    @Test
    @DisplayName("首次登记额度大于全站额度：启动失败（免登录入口不能比登录用户还宽松）")
    void rejectsJoinLimitGreaterThanLimit() {
        AppProperties props = valid();
        props.getRateLimit().setLimit(10);
        props.getRateLimit().setLoginLimit(10);
        props.getRateLimit().setJoinLimit(60);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RATE_LIMIT_JOIN_LIMIT 不能大于");
    }

    @Test
    @DisplayName("免登录额度等于登录额度是允许的（只是不更严，不是错误配置）")
    void allowsEqualLimits() {
        AppProperties props = valid();
        props.getRateLimit().setLimit(30);
        props.getRateLimit().setLoginLimit(30);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    /** 其余必填项按最小合法值填好，让校验能走到限流那几条 */
    private static AppProperties valid() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(JWT_SECRET);
        props.getWx().setAppId("wx-test-appid");
        props.getWx().setAppSecret("wx-test-secret");
        props.getUpload().setDir("/tmp/upload");
        return props;
    }
}

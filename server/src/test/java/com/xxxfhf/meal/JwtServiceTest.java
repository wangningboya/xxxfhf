package com.xxxfhf.meal;

import com.xxxfhf.meal.auth.JwtService;
import com.xxxfhf.meal.config.AppProperties;
import com.xxxfhf.meal.user.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 签发与校验的纯单元测试，不依赖 Spring 上下文与数据库。
 */
class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private JwtService newService(int expireDays) {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setExpireDays(expireDays);
        return new JwtService(props);
    }

    @Test
    @DisplayName("签发后能解析出 userId 与账号类型")
    void issueAndParse() {
        JwtService service = newService(7);
        String token = service.issue("u_1001", UserType.CUSTOMER, 0);

        Optional<JwtService.TokenPayload> payload = service.parse(token);
        assertThat(payload).isPresent();
        assertThat(payload.get().userId()).isEqualTo("u_1001");
        assertThat(payload.get().userType()).isEqualTo(UserType.CUSTOMER);
    }

    @Test
    @DisplayName("被篡改的令牌解析失败，不抛异常")
    void tamperedTokenRejected() {
        JwtService service = newService(7);
        String token = service.issue("u_1001", UserType.CUSTOMER, 0);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(service.parse(tampered)).isEmpty();
    }

    @Test
    @DisplayName("其他密钥签发的令牌不被接受")
    void tokenFromOtherSecretRejected() {
        String foreign = newService(7).issue("u_9999", UserType.MERCHANT, 0);
        AppProperties other = new AppProperties();
        other.getJwt().setSecret("ffffffffffffffffffffffffffffffff");
        other.getJwt().setExpireDays(7);
        JwtService another = new JwtService(other);

        assertThat(another.parse(foreign)).isEmpty();
    }

    @Test
    @DisplayName("过期令牌解析失败")
    void expiredTokenRejected() {
        // 负数表示过期时间在过去，仅测试用（生产配置由 AppProperties 限制在 1~30 天）
        JwtService service = newService(-1);
        String token = service.issue("u_1001", UserType.CUSTOMER, 0);

        assertThat(service.parse(token)).isEmpty();
    }

    @Test
    @DisplayName("非 JWT 字符串解析失败")
    void garbageRejected() {
        assertThat(newService(7).parse("not-a-jwt")).isEmpty();
    }
}

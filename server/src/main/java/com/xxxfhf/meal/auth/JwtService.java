package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验。
 *
 * 令牌里只放 userId 与账号类型，不放审核状态与权限项：
 * 审核通过、权限变更必须立刻生效，把这些放进 7 天有效的令牌里会造成「审核过了但下单仍被拦」。
 *
 * 注意：令牌里的类型只作参考，拦截器每次都从库里读 user_type 为准，
 * 因此商家被降级后，手里的旧令牌不会继续拥有后台权限。
 */
@Service
public class JwtService {

    private static final String ISSUER = "meal-order";
    private static final String CLAIM_TYPE = "typ";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofDays(props.getJwt().getExpireDays());
    }

    /**
     * @param userId   用户主键
     * @param userType customer / merchant（见 UserType）
     */
    public String issue(String userId, String userType) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim(CLAIM_TYPE, userType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 校验签名与过期时间，失败返回 empty（不抛异常，由调用方决定返回 401）。 */
    public Optional<TokenPayload> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            String userType = claims.get(CLAIM_TYPE, String.class);
            if (userId == null || userId.isBlank() || userType == null || userType.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TokenPayload(userId, userType));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record TokenPayload(String userId, String userType) {
    }
}

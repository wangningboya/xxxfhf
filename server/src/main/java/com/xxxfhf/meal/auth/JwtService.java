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
 * 令牌里只放 userId、账号类型与令牌版本号，不放审核状态与权限项：
 * 审核通过、权限变更必须立刻生效，把这些放进 7 天有效的令牌里会造成「审核过了但下单仍被拦」。
 *
 * 版本号（ver）用于主动踢人：库里 token_version +1 后，旧令牌在拦截器比对时被拒。
 * 注意：令牌里的类型只作参考，拦截器每次都从库里读 user_type 为准，
 * 因此商家被降级后，手里的旧令牌不会继续拥有后台权限。
 */
@Service
public class JwtService {

    private static final String ISSUER = "meal-order";
    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_VERSION = "ver";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofDays(props.getJwt().getExpireDays());
    }

    /**
     * @param userId       用户主键
     * @param userType     customer / merchant（见 UserType）
     * @param tokenVersion 签发时的 user.token_version，拦截器据此比对踢人
     */
    public String issue(Long userId, String userType, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, userType)
                .claim(CLAIM_VERSION, tokenVersion)
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
            String subject = claims.getSubject();
            String userType = claims.get(CLAIM_TYPE, String.class);
            // ver 必须按 Number 读：jjwt-gson 会把数字 claim 反序列化成 Double，
            // 按 Integer 强转取值会抛 RequiredTypeException，被 catch 成「令牌无效」
            Object versionRaw = claims.get(CLAIM_VERSION);
            if (subject == null || subject.isBlank() || userType == null || userType.isBlank()
                    || !(versionRaw instanceof Number versionNumber)) {
                return Optional.empty();
            }
            // 主键是数据库自增 BIGINT，而 JWT subject 只能是字符串，故此处还原成 Long；
            // 脏数据会抛 NumberFormatException（IllegalArgumentException 子类），落到下面的 catch
            return Optional.of(new TokenPayload(Long.valueOf(subject.trim()), userType, versionNumber.intValue()));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record TokenPayload(Long userId, String userType, int tokenVersion) {
    }
}

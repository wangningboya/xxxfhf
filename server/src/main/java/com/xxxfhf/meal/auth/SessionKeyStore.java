package com.xxxfhf.meal.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * session_key 的进程内存储。
 *
 * 这是微信下发的会话密钥，只用于解密 openGId / 手机号等加密数据，绝不能返回给前端。
 * 它属于登录态而非业务数据，故不入库：
 * 服务重启后丢失 —— 此时解密 openGId 会失败，前端重新 wx.login 即可恢复（用户无感）。
 *
 * 若后续要求重启不丢，在 user 表加一列 session_key 后把这里换成 DB 存取即可，调用方无需改动。
 */
@Component
public class SessionKeyStore {

    private static final Logger log = LoggerFactory.getLogger(SessionKeyStore.class);
    private static final Duration TTL = Duration.ofDays(7);

    private record Entry(String sessionKey, Instant expireAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public void put(String openid, String sessionKey) {
        if (openid == null || openid.isBlank() || sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        store.put(openid, new Entry(sessionKey, Instant.now().plus(TTL)));
        if (store.size() > 5000) {
            // 单群规模下不会到这一步；万一异常增长，先清理过期项兜底
            evictExpired();
            log.warn("session_key 存储条数超过 5000，已触发清理，当前 {}", store.size());
        }
    }

    public Optional<String> get(String openid) {
        Entry entry = store.get(openid);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expireAt().isBefore(Instant.now())) {
            store.remove(openid);
            return Optional.empty();
        }
        return Optional.of(entry.sessionKey());
    }

    private void evictExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expireAt().isBefore(now));
    }
}

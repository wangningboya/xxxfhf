package com.xxxfhf.meal.auth.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 密码哈希（BCrypt）。仅用于商家账号，群成员走微信 openid 不涉及密码。
 *
 * 初始账号的哈希由 AdminAccountInitializer 在启动时用本类生成，
 * 不在 SQL 里写死哈希值——前缀（$2a$ / $2b$ / $2y$）不一致会让「密码对却登不上」。
 */
@Service
public class PasswordService {

    /** 强度 10：单次校验约 50~80ms，兼顾安全与登录体验 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String hashed) {
        if (rawPassword == null || hashed == null || hashed.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, hashed);
    }
}

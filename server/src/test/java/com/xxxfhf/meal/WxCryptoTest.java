package com.xxxfhf.meal;

import com.xxxfhf.meal.auth.WxCrypto;
import com.xxxfhf.meal.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信加密数据解密（openGId / 手机号）。
 *
 * 这块是本项目准入的根基：解错了不是「报错」，而是「谁都进不来」或「谁都能进来」。
 * 用真实 AES-128-CBC 加密再解，比 mock 掉解密过程有意义得多。
 */
class WxCryptoTest {

    /** 16 字节密钥与向量：AES-128 要求 key/iv 都是 16 字节 */
    private static final byte[] KEY = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IV = "abcdef0123456789".getBytes(StandardCharsets.UTF_8);

    private final WxCrypto crypto = new WxCrypto(JsonMapper.builder().build());

    @Test
    @DisplayName("能从加密数据里解出 openGId")
    void decodeOpenGId() throws Exception {
        String encrypted = encrypt("{\"openGId\":\"G_TEST_GROUP_001\",\"watermark\":{}}");

        String openGId = crypto.requireOpenGId(b64(KEY), encrypted, b64(IV));

        assertThat(openGId).isEqualTo("G_TEST_GROUP_001");
    }

    @Test
    @DisplayName("字段名是 open_gid / openGid 的变体也能兜住")
    void decodeOpenGIdVariantFieldName() throws Exception {
        assertThat(crypto.requireOpenGId(b64(KEY), encrypt("{\"open_gid\":\"G_A\"}"), b64(IV)))
                .isEqualTo("G_A");
        assertThat(crypto.requireOpenGId(b64(KEY), encrypt("{\"openGid\":\"G_B\"}"), b64(IV)))
                .isEqualTo("G_B");
    }

    @Test
    @DisplayName("密钥不对时解不出内容，且不抛底层异常")
    void wrongKeyReturnsNull() throws Exception {
        String encrypted = encrypt("{\"openGId\":\"G_TEST\"}");
        byte[] wrongKey = "ffffffffffffffff".getBytes(StandardCharsets.UTF_8);

        assertThat(crypto.decrypt(b64(wrongKey), encrypted, b64(IV))).isNull();
    }

    @Test
    @DisplayName("解密结果里没有 openGId 时抛业务异常，而不是返回空串")
    void missingOpenGIdThrows() throws Exception {
        String encrypted = encrypt("{\"foo\":\"bar\"}");

        assertThatThrownBy(() -> crypto.requireOpenGId(b64(KEY), encrypted, b64(IV)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("群标识");
    }

    @Test
    @DisplayName("入参缺失或不是合法 base64 时返回 null，不抛异常")
    void malformedInputReturnsNull() {
        assertThat(crypto.decrypt(null, "abc", b64(IV))).isNull();
        assertThat(crypto.decrypt(b64(KEY), "", b64(IV))).isNull();
        assertThat(crypto.decrypt(b64(KEY), "!!!not-base64!!!", b64(IV))).isNull();
    }

    /* ---------------- 辅助 ---------------- */

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(IV));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String b64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }
}

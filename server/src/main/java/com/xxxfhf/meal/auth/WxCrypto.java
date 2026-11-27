package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 解密微信侧的加密数据（openGId、手机号）。
 *
 * 算法固定：AES-128-CBC + PKCS#7 填充，密钥是 base64(session_key)，向量是 base64(iv)。
 * 全部在服务端完成，session_key 与明文都不出服务端。
 *
 * 失败一律按「解不出」处理，不给调用方任何内部细节：解不出就不可信，不能拿来做准入判断。
 */
@Component
public class WxCrypto {

    private static final Logger log = LoggerFactory.getLogger(WxCrypto.class);
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    private final ObjectMapper objectMapper;

    public WxCrypto(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param sessionKey    该用户的 session_key（明文，来自 {@link SessionKeyStore}）
     * @param encryptedData 微信下发的密文
     * @param iv            微信下发的向量
     * @return 解密后的 JSON；任一环节失败返回 null，由调用方决定如何提示
     */
    public JsonNode decrypt(String sessionKey, String encryptedData, String iv) {
        if (isBlank(sessionKey) || isBlank(encryptedData) || isBlank(iv)) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(sessionKey);
            byte[] ivBytes = Base64.getDecoder().decode(iv);
            byte[] dataBytes = Base64.getDecoder().decode(encryptedData);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new IvParameterSpec(ivBytes));
            byte[] plain = cipher.doFinal(dataBytes);

            JsonNode node = objectMapper.readTree(new String(plain, StandardCharsets.UTF_8));
            if (node == null || node.isNull()) {
                return null;
            }
            return node;
        } catch (Exception ex) {
            // 密钥过期、数据被改、格式不对都会走到这里。不区分原因，也不把密文写进日志。
            log.warn("微信加密数据解密失败：{}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 解 openGId。取不到就抛错——准入判断不能靠猜。
     */
    public String requireOpenGId(String sessionKey, String encryptedData, String iv) {
        JsonNode node = decrypt(sessionKey, encryptedData, iv);
        if (node == null) {
            throw new BizException(ErrorCode.WX_API_ERROR, "群信息校验失败，请重新进入小程序，或改用邀请码进入");
        }
        String openGId = node.path("openGId").asText("");
        if (openGId.isBlank()) {
            // 微信偶尔会把 openGId 放在 open_gid / openGid 里，一并兜住
            openGId = node.path("open_gid").asText(node.path("openGid").asText(""));
        }
        if (openGId.isBlank()) {
            log.warn("解密结果中没有 openGId 字段");
            throw new BizException(ErrorCode.WX_API_ERROR, "未获取到群标识，请改用邀请码进入");
        }
        return openGId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

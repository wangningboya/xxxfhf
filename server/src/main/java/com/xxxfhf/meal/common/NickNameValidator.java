package com.xxxfhf.meal.common;

import java.util.regex.Pattern;

/**
 * 昵称清洗与校验。
 *
 * 微信自 2022-10-25 起不再返回真实昵称（wx.getUserProfile 只给「微信用户」+ 灰头像），
 * 昵称改由用户在小程序「昵称填写能力」里点选微信昵称或手动输入，属于**用户自由输入的文本**，
 * 所以这里统一做三件事：去首尾空白、压内部空白、限长度。
 *
 * 敏感词**不在这里做**：统一走微信 msg_sec_check 2.0（{@link ContentSecurityService}），
 * 判定口径见技术文档 4.18。本类只管清洗与长度，别在这里另造一套词库。
 */
public final class NickNameValidator {

    /** 上限 32（按 Java 字符计，一个 emoji 占 2），比 user.nick_name 的 VARCHAR(64) 留足余量。 */
    public static final int MAX_LENGTH = 32;

    /** 连续空白（含全角空格、不换行空格）压成一个半角空格，防后台审核列表被撑乱。 */
    private static final Pattern BLANK_RUN = Pattern.compile("[\\s\\u00A0\\u3000]+");

    private NickNameValidator() {
    }

    /**
     * 清洗并返回昵称；空、纯空白、超长都直接抛可展示的错误。
     *
     * 长度按 Java 字符算，emoji 占 2 个——上限取 32 而不是贴着列宽卡，
     * 就是给 emoji 昵称留余量，避免「看着 20 个字却存不进去」。
     */
    public static String require(String raw) {
        if (raw == null) {
            throw BizException.param("请填写昵称");
        }
        String cleaned = BLANK_RUN.matcher(raw).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            throw BizException.param("请填写昵称");
        }
        if (cleaned.length() > MAX_LENGTH) {
            throw BizException.param("昵称最多 " + MAX_LENGTH + " 个字");
        }
        return cleaned;
    }
}

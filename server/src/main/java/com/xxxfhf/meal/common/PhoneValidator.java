package com.xxxfhf.meal.common;

import java.util.regex.Pattern;

/**
 * 手机号格式校验。
 *
 * 需求只要求「11 位大陆手机号」格式校验，不校验归属地、不发短信验证码。
 */
public final class PhoneValidator {

    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    private PhoneValidator() {
    }

    public static boolean isValid(String phone) {
        return phone != null && MOBILE.matcher(phone.trim()).matches();
    }

    /** 校验并返回去掉首尾空白的值，不合法直接抛可展示的错误。 */
    public static String require(String phone) {
        String trimmed = phone == null ? "" : phone.trim();
        if (!isValid(trimmed)) {
            throw BizException.param("手机号格式不正确，请输入 11 位手机号");
        }
        return trimmed;
    }
}

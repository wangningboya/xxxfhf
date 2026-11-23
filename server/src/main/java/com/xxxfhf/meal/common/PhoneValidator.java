package com.xxxfhf.meal.common;

import java.util.regex.Pattern;

/**
 * 手机号格式校验。
 *
 * 需求只要求「11 位大陆手机号」格式校验，不校验归属地、不发短信验证码。
 */
public final class PhoneValidator {

    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    /** 座机：0 开头区号 3~4 位 + 号码 7~8 位，允许中间一个短横线。 */
    private static final Pattern LANDLINE = Pattern.compile("^0\\d{2,3}-?\\d{7,8}$");

    private PhoneValidator() {
    }

    public static boolean isValid(String phone) {
        return phone != null && MOBILE.matcher(phone.trim()).matches();
    }

    /** 商家联系电话：手机号或带区号座机都收，店主用店里座机是很常见的情况。 */
    public static boolean isValidShopPhone(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return MOBILE.matcher(trimmed).matches() || LANDLINE.matcher(trimmed).matches();
    }

    /** 校验并返回去掉首尾空白的值，不合法直接抛可展示的错误。 */
    public static String require(String phone) {
        String trimmed = phone == null ? "" : phone.trim();
        if (!isValid(trimmed)) {
            throw BizException.param("手机号格式不正确，请输入 11 位手机号");
        }
        return trimmed;
    }

    /** 校验商家联系电话（可空：留空表示不展示拨号入口）。 */
    public static void requireShopPhone(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!isValidShopPhone(value)) {
            throw BizException.param("商家联系电话格式不正确，请填 11 位手机号或带区号的座机（如 010-12345678）");
        }
    }
}

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

    /**
     * 可选手机号：空值放行并返回 null，填了就必须合法。
     *
     * 用于收货地址 —— 自取单不需要手机号（需求 4.8），但配送单需要；
     * 同一个地址表两种场景共用，所以只能「填了才校验」，不能强制必填。
     */
    public static String optional(String phone) {
        String trimmed = phone == null ? "" : phone.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return require(trimmed);
    }
}

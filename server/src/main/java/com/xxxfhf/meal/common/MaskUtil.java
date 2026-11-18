package com.xxxfhf.meal.common;

/**
 * 手机号掩码。
 *
 * <p>两种口径，别混用：
 * <ul>
 *   <li>{@link #phone(String)}：前 3 后 4（{@code 138****1234}）。后台核对用——<br>
 *       客服/商家要能把「这个号对不对」跟用户对上一次，尾部 4 位是唯一能核对的部分。</li>
 *   <li>{@link #phoneShort(String)}：前 3 后 2（{@code 156******77}）。订单、导出、对外展示用——<br>
 *       微信《小程序安全指南》给的常用脱敏示例就是这个位数，够认出是谁，不够拼出完整号。</li>
 * </ul>
 *
 * <p>口径统一放在这里，理由很实际：同一个人在两处看到不同位数的掩码，
 * 会以为是两个号。真要查完整号，走带权限与操作记录的专用接口，不要在展示层各写一套。
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /** 前 3 后 4：{@code 138****1234}。后台核对用。 */
    public static String phone(String phone) {
        return mask(phone, 4);
    }

    /** 前 3 后 2：{@code 156******77}。订单、导出、对外展示用。 */
    public static String phoneShort(String phone) {
        return mask(phone, 2);
    }

    /**
     * @param tail 保留的尾号位数
     */
    private static String mask(String phone, int tail) {
        if (phone == null) {
            return null;
        }
        String value = phone.trim();
        if (value.isEmpty()) {
            return null;
        }
        int head = 3;
        // 位数不够就整串打码，不拼接半截：拼出来反而像真号，更容易被误当成完整号码
        if (value.length() <= head + tail) {
            return "*".repeat(value.length());
        }
        return value.substring(0, head)
                + "*".repeat(value.length() - head - tail)
                + value.substring(value.length() - tail);
    }
}

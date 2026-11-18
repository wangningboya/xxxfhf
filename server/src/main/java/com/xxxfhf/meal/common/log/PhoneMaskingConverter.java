package com.xxxfhf.meal.common.log;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.xxxfhf.meal.common.MaskUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志兜底脱敏：把日志正文里的 11 位手机号替换成前 3 后 4。
 *
 * <p>为什么要在日志框架这一层再做一次：业务代码已经刻意不打全号了，
 * 但「将来某个人随手 {@code log.info("号码={}", phone)}」这种事拦不住 —— 这类日志一旦进了
 * 文件与采集系统，删库也追不回来。在出口统一过一遍，才叫兜底。
 *
 * <p>只认 {@code 1[3-9]\\d{9}} 这一种形态：宁可漏掉个别变体，也不要误伤订单号、金额、
 * 时间戳这类纯数字串 —— 掩码错位的成本比漏掩一次高得多。
 *
 * <p>掩码实现复用 {@link MaskUtil#phone(String)}，全站只有一份掩码规则。
 */
public class PhoneMaskingConverter extends ClassicConverter {

    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");

    @Override
    public String convert(ILoggingEvent event) {
        return mask(event.getFormattedMessage());
    }

    /** 纯函数，便于单测；日志正文为空时原样返回。 */
    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        Matcher matcher = MOBILE.matcher(message);
        if (!matcher.find()) {
            return message;
        }
        StringBuilder masked = new StringBuilder(message.length());
        int last = 0;
        do {
            masked.append(message, last, matcher.start())
                    .append(MaskUtil.phone(matcher.group()));
            last = matcher.end();
        } while (matcher.find());
        masked.append(message, last, message.length());
        return masked.toString();
    }
}

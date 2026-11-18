package com.xxxfhf.meal.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneMaskingConverterTest {

    @Test
    @DisplayName("单个号码：日志正文里的 11 位号被前 3 后 4 替换")
    void masksSinglePhone() {
        assertThat(PhoneMaskingConverter.mask("用户手机号 13812341234 已更新"))
                .isEqualTo("用户手机号 138****1234 已更新");
    }

    @Test
    @DisplayName("多个号码：每一处都被替换")
    void masksEveryPhone() {
        assertThat(PhoneMaskingConverter.mask("15612345677 -> 13912345678"))
                .isEqualTo("156****5677 -> 139****5678");
    }

    @Test
    @DisplayName("没有号码的日志原样输出")
    void keepsTextWithoutPhone() {
        assertThat(PhoneMaskingConverter.mask("订单 20261118-000001 金额 1500"))
                .isEqualTo("订单 20261118-000001 金额 1500");
    }

    @Test
    @DisplayName("不是手机号的数字串不动：以 1 开头但第二位不在 3-9 的串")
    void keepsNonMobileDigits() {
        assertThat(PhoneMaskingConverter.mask("12345678901 10000000000"))
                .isEqualTo("12345678901 10000000000");
    }

    @Test
    @DisplayName("空正文不抛异常")
    void handlesBlank() {
        assertThat(PhoneMaskingConverter.mask(null)).isNull();
        assertThat(PhoneMaskingConverter.mask("")).isEmpty();
    }
}

package com.xxxfhf.meal.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 掩码口径的底线：前 3 位与尾号必须保住，中间必须被星号覆盖，长度不对也不能原样漏出去。
 */
class MaskUtilTest {

    @Test
    void phoneKeepsHeadThreeAndTailFour() {
        assertThat(MaskUtil.phone("13812341234")).isEqualTo("138****1234");
    }

    @Test
    void phoneShortKeepsHeadThreeAndTailTwo() {
        assertThat(MaskUtil.phoneShort("15612345677")).isEqualTo("156******77");
    }

    @Test
    void nullAndBlankStayNull() {
        assertThat(MaskUtil.phone(null)).isNull();
        assertThat(MaskUtil.phoneShort(null)).isNull();
        assertThat(MaskUtil.phone("   ")).isNull();
        assertThat(MaskUtil.phoneShort("   ")).isNull();
    }

    @Test
    void tooShortIsFullyMaskedInsteadOfLeakingHalf() {
        // 位数不足时整串打码：拼出半截号码反而像真号，更容易被当成完整号码转走
        assertThat(MaskUtil.phone("12345")).isEqualTo("*****");
        assertThat(MaskUtil.phoneShort("1234")).isEqualTo("****");
    }

    @Test
    void trimsBeforeMasking() {
        assertThat(MaskUtil.phone(" 13812341234 ")).isEqualTo("138****1234");
    }
}

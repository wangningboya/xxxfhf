package com.xxxfhf.meal;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.NickNameValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 昵称清洗。
 *
 * 微信不再返回真实昵称，昵称是用户手输的自由文本，落库前必须收敛：
 * 空、纯空白、超长都要拦，空白要压平，否则后台审核列表会被撑乱。
 */
class NickNameValidatorTest {

    @Test
    @DisplayName("正常中文昵称原样返回")
    void normalChineseName() {
        assertThat(NickNameValidator.require("张三")).isEqualTo("张三");
    }

    @Test
    @DisplayName("首尾空白被裁掉")
    void trimBothEnds() {
        assertThat(NickNameValidator.require("  李四  ")).isEqualTo("李四");
    }

    @Test
    @DisplayName("换行与制表符压成一个空格")
    void collapseLineBreaks() {
        assertThat(NickNameValidator.require("王\n五\t同学")).isEqualTo("王 五 同学");
    }

    @Test
    @DisplayName("全角空格也算空白，一并压平")
    void collapseFullWidthSpace() {
        assertThat(NickNameValidator.require("赵\u3000六")).isEqualTo("赵 六");
    }

    @Test
    @DisplayName("中间单个空格保留，不做多余改写")
    void keepSingleInnerSpace() {
        assertThat(NickNameValidator.require("Tom Cruise")).isEqualTo("Tom Cruise");
    }

    @Test
    @DisplayName("纯空白视为未填")
    void blankOnlyRejected() {
        assertThatThrownBy(() -> NickNameValidator.require("  \n\t "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请填写昵称");
    }

    @Test
    @DisplayName("null 视为未填")
    void nullRejected() {
        assertThatThrownBy(() -> NickNameValidator.require(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请填写昵称");
    }

    @Test
    @DisplayName("恰好 32 字放行，33 字拒绝")
    void lengthBoundary() {
        String ok = "字".repeat(NickNameValidator.MAX_LENGTH);
        assertThat(NickNameValidator.require(ok)).hasSize(NickNameValidator.MAX_LENGTH);

        String tooLong = "字".repeat(NickNameValidator.MAX_LENGTH + 1);
        assertThatThrownBy(() -> NickNameValidator.require(tooLong))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多 32 个字");
    }

    @Test
    @DisplayName("16 个 emoji（占 32 长度）能存进去")
    void emojiNameFits() {
        // 一个 emoji 在 Java 里是 2 个 char，16 个正好 32；这也是上限取 32 而非 64 的原因
        String emoji = "🍚".repeat(16);
        assertThat(NickNameValidator.require(emoji)).hasSize(32);
    }
}

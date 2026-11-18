package com.xxxfhf.meal.prep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 备菜 Excel 的单元格转义。
 *
 * <p>只测纯函数：这条链路的输入是用户填的姓名与地址，跟数据库、Excel 库都无关，
 * 一旦有人把它「优化」掉，用单测盯着最省事。
 */
class PrepServiceTest {

    @Test
    @DisplayName("等号开头的姓名加上单引号，Excel 不再当公式")
    void escapesFormulaStart() {
        assertThat(PrepService.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(PrepService.cell("+86")).isEqualTo("'+86");
        assertThat(PrepService.cell("-2")).isEqualTo("'-2");
        assertThat(PrepService.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    @DisplayName("普通姓名、空值、null 原样返回")
    void keepsPlainText() {
        assertThat(PrepService.cell("张三")).isEqualTo("张三");
        assertThat(PrepService.cell("")).isEqualTo("");
        assertThat(PrepService.cell(null)).isEqualTo("");
    }

    @Test
    @DisplayName("只处理开头，中间出现等号不动")
    void onlyChecksFirstChar() {
        assertThat(PrepService.cell("A=1")).isEqualTo("A=1");
    }
}

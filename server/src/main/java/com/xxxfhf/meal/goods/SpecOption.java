package com.xxxfhf.meal.goods;

/**
 * 规格组里的一个选项，存在 `spec_group`.`options` 这个 JSON 数组里。
 *
 * @param optionId   选项 ID，前端下单时回传它
 * @param name       选项名，如「白米饭」「杂粮饭」
 * @param priceDelta **单价差价，单位分**。0 表示不加价，不是「0 份」。
 *                   注意是「选它比基准多收多少钱」，与份数无关
 * @param sort       展示顺序
 */
public record SpecOption(String optionId, String name, Integer priceDelta, Integer sort) {

    public int deltaOrZero() {
        return priceDelta == null ? 0 : priceDelta;
    }

    public int sortOrZero() {
        return sort == null ? 0 : sort;
    }
}

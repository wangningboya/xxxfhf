package com.xxxfhf.meal.goods.domain;

/**
 * 盒饭里的一道菜，存在 `combo`.`dishes` 这个 JSON 数组里。
 *
 * @param dishId 菜品 ID
 * @param name   **菜名快照**。菜品改名后，已配好的盒饭仍显示下单时的名字——
 *               否则商家改个菜名，所有盒饭的组成会同时变样，且无法解释给用户
 * @param qty    该菜在一份盒饭里的份数，默认 1，**不限上限**（例汤 2 份是合理需求）。
 *               备菜统计按「盒饭份数 × 本字段」累加
 */
public record ComboDish(Long dishId, String name, Integer qty) {

    /** 份数缺省为 1：历史数据或前端漏传时按 1 份算，不因为一个空值把整份盒饭判为非法。 */
    public int qtyOrDefault() {
        return qty == null || qty < 1 ? 1 : qty;
    }
}

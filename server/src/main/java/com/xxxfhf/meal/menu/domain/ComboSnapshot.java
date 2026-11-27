package com.xxxfhf.meal.menu.domain;

import com.xxxfhf.meal.goods.domain.ComboDish;
import com.xxxfhf.meal.goods.domain.SpecOption;

import java.util.List;

/**
 * 菜单里的盒饭快照。存进 {@code daily_menu.combo_snapshot} 这个 JSON 列。
 *
 * 快照三件套（菜单 → 订单 → 备菜）是同一条链：
 * 商家改盒饭配菜不影响已发布菜单，用户下单后改菜单也不影响已有订单。
 *
 * @param name       盒饭名称快照
 * @param cover      封面图
 * @param dishes     组成菜品与份数快照，备菜统计的唯一依据
 * @param specGroups 启用规格组的完整快照（含选项与差价）。
 *                   订单要按「当时的选项与差价」记录，所以这里存的是内容而不是 ID
 */
public record ComboSnapshot(String name, String cover, List<ComboDish> dishes,
                            List<SpecGroupSnapshot> specGroups) {

    /**
     * 规格组快照。
     *
     * @param countInPrep 是否计入备菜。备菜统计要按「下单那一刻」的口径算，
     *                    否则商家中途把辣度改成计入备菜，历史统计会凭空多出一批数据
     */
    public record SpecGroupSnapshot(String name, Boolean required, Boolean countInPrep,
                                    List<SpecOption> options) {
    }
}

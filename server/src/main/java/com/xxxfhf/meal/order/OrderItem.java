package com.xxxfhf.meal.order;

import com.xxxfhf.meal.goods.ComboDish;

import java.util.List;

/**
 * 订单明细的一项。存在 `order`.`items` 这个 JSON 数组里。
 *
 * 全部是**快照**：盒饭名、单价、组成菜品与份数、所选规格的名称与差价。
 * 备菜统计只读这里，不 join 实时表 —— 改盒饭配菜、改规格差价、改盒饭名，都不影响历史订单。
 *
 * @param price 单价快照，单位分（取的是菜单发布时的价格快照，不是盒饭库当前价）
 * @param specs 所选规格，存名称与差价而不是 ID：选项被删了也能还原当时选的是什么
 */
public record OrderItem(Long comboId, String name, Integer price, Integer qty,
                        List<ComboDish> dishes, List<Spec> specs) {

    /**
     * @param groupName   组名快照，如「主食」
     * @param optionName  选项名快照，如「杂粮饭」
     * @param priceDelta  该选项的差价快照（分）
     * @param countInPrep 该组是否计入备菜（下单那一刻的口径）。
     *                    必须快照：商家中途把辣度改成「计入备菜」，历史统计不该凭空多出一批数据
     */
    public record Spec(String groupName, String optionName, Integer priceDelta, Boolean countInPrep) {
    }

    public int qtyOrZero() {
        return qty == null ? 0 : qty;
    }

    public int priceOrZero() {
        return price == null ? 0 : price;
    }

    /** 本行商品金额（分）= 单价 × 份数。不含规格差价。 */
    public int lineGoodsAmount() {
        return priceOrZero() * qtyOrZero();
    }

    /** 本行规格差价合计（分）= Σ 差价 × 份数。 */
    public int lineSpecAmount() {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        int delta = 0;
        for (Spec spec : specs) {
            delta += spec.priceDelta() == null ? 0 : spec.priceDelta();
        }
        return delta * qtyOrZero();
    }
}

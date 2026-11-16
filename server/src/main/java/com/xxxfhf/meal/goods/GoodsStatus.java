package com.xxxfhf.meal.goods;

/**
 * 商品域状态字面量，与建表脚本的注释保持一致。
 *
 * 为什么菜品 / 规格组用 enabled，而盒饭用 on/off：这不是我随意定的，
 * 建表脚本里 `dish`.`status` 的注释就是 enabled/disabled，`combo`.`status` 是 on/off。
 * 两套字面量并存看着别扭，但改列注释等于改数据约定，得不偿失，所以按先有的事实走。
 */
public final class GoodsStatus {

    private GoodsStatus() {
    }

    /** 菜品 / 规格组：启用 */
    public static final String ENABLED = "enabled";
    /** 菜品 / 规格组：停用 */
    public static final String DISABLED = "disabled";

    /** 盒饭：上架 */
    public static final String ON = "on";
    /** 盒饭：下架 */
    public static final String OFF = "off";
}

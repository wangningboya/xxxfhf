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

    /**
     * 状态存值的中文名，给操作记录摘要用。
     *
     * <p>两套字面量各有一份说法：菜品 / 规格组叫「启用 / 停用」，盒饭叫「上架 / 下架」——
     * 商家在这两个页面上的按钮文案就是这个，日志里换成另一套会让人对不上点的是哪个。
     * 未知值原样返回，绝不出现空串。
     */
    public static String label(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ENABLED -> "启用";
            case DISABLED -> "停用";
            case ON -> "上架";
            case OFF -> "下架";
            default -> status;
        };
    }
}

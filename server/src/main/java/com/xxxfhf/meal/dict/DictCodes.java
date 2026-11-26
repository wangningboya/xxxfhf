package com.xxxfhf.meal.dict;

/**
 * 字典类型编码。代码里一律用这里的常量取值，不写字面量。
 *
 * 与 `deploy/mysql/init/02-data.sql` 的 dict_type 初始数据一一对应。
 */
public final class DictCodes {

    private DictCodes() {
    }

    public static final String ORDER_STATUS = "order_status";
    public static final String PAY_STATUS = "pay_status";
    public static final String ADDRESS_STATUS = "address_status";
    public static final String AUDIT_STATUS = "audit_status";
    public static final String INVITE_STATUS = "invite_status";
    public static final String DELIVERY_TYPE = "delivery_type";
    public static final String NOTIFY_TYPE = "notify_type";

    /**
     * 收款方式。与其他内置类型不同，**此项允许商家自行增删字典项**（见
     * {@code DictService.EXTENSIBLE_TYPES}）：收款渠道不是状态机的一环，
     * 多一个「银行转账」不会让代码走到处理不了的分支。
     */
    public static final String PAY_METHOD = "pay_method";

    /**
     * 商品分类（盒饭 / 自制菜）。挂在 {@code combo.category_code} 上，存的是**存值**而不是字典项 ID。
     *
     * <p>同样是「内置类型 + 允许增项」：分类只决定点餐页怎么分组展示，不参与任何履约分支，
     * 商家加一个「卤味」不会让代码走到不认识的地方。种子里「盒饭 / 自制菜」两项是内置项，
     * 只能停用、不能删也不能改存值。
     */
    public static final String COMBO_CATEGORY = "combo_category";

    /**
     * 原料分类。挂在 {@code dish.category_code} 上，本期**只落字段、不预置项、不做界面**，
     * 留给将来做「备菜按原料分类分组」。它是自定义类型（商家可在字典页自建项）。
     */
    public static final String DISH_CATEGORY = "dish_category";
}

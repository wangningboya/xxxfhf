package com.xxxfhf.meal.auth;

/**
 * 权限项清单，与《需求文档》第二章「权限项清单」一一对应。
 *
 * 前端隐藏按钮只是体验优化，判定一律在服务端做。
 */
public final class Perms {

    private Perms() {
    }

    /** 菜品（原料）增删改、启停用 */
    public static final String DISH_MANAGE = "dish.manage";
    /** 盒饭新增、编辑组成、定价、上下架 */
    public static final String COMBO_MANAGE = "combo.manage";
    /** 每日菜单勾选盒饭、设置份数 */
    public static final String MENU_MANAGE = "menu.manage";
    /** 审核用户准入、禁用用户 */
    public static final String USER_AUDIT = "user.audit";
    /** 只读查看用户列表与审核状态 */
    public static final String USER_VIEW = "user.view";
    /** 区域新增、改名、排序、停用 */
    public static final String REGION_MANAGE = "region.manage";
    /** 维护用户收货地址 */
    public static final String USER_ADDRESS = "user.address";
    /** 接单、拒单、推进订单状态 */
    public static final String ORDER_ACCEPT = "order.accept";
    /** 只读查看订单列表与详情 */
    public static final String ORDER_VIEW = "order.view";
    /** 查看备菜统计 */
    public static final String PREP_VIEW = "prep.view";
    /** 导出备菜与对账 Excel */
    public static final String PREP_EXPORT = "prep.export";
    /** 确认收款、标记退款 */
    public static final String ORDER_PAYMENT = "order.payment";
    /** 查看对账视图 */
    public static final String FINANCE_VIEW = "finance.view";
    /** 截单时间、配送费、店铺信息 */
    public static final String CONFIG_MANAGE = "config.manage";
    /** 角色与权限分配 */
    public static final String ROLE_MANAGE = "role.manage";
    /** 数据字典维护 */
    public static final String DICT_MANAGE = "dict.manage";

    /** 内置「商家」角色的全部权限。 */
    public static final String[] ALL = {
            DISH_MANAGE, COMBO_MANAGE, MENU_MANAGE,
            USER_AUDIT, USER_VIEW,
            REGION_MANAGE, USER_ADDRESS,
            ORDER_ACCEPT, ORDER_VIEW,
            PREP_VIEW, PREP_EXPORT,
            ORDER_PAYMENT, FINANCE_VIEW,
            CONFIG_MANAGE, ROLE_MANAGE, DICT_MANAGE
    };
}

package com.xxxfhf.meal.audit;

import java.util.List;

/**
 * 操作记录的动作码清单。
 *
 * <p>一句话区分「动作」和「对象」：{@code table_name} 说动的是哪类东西，
 * {@code action} 说具体做了什么。两者拼起来才是完整含义（{@code order} + {@code order.pay.confirm}）。
 *
 * <p><b>本清单同时是筛选下拉的数据源</b>（{@code GET /admin/audit-log/actions}）。
 * 新增一个埋点必须在这里加一项，否则页面上筛不到 —— 与权限码要同步加进角色页正则同类，
 * 都是「漏了不报错、只是安静地少一块」。
 */
public final class AuditActions {

    /* ---------------- 对象表 ---------------- */

    public static final String TABLE_ADDRESS = "address";
    public static final String TABLE_USER = "user";
    public static final String TABLE_ORDER = "order";

    /* ---------------- 地址 ---------------- */

    public static final String ADDRESS_CREATE = "address.create";
    public static final String ADDRESS_UPDATE = "address.update";
    public static final String ADDRESS_APPROVE = "address.approve";
    public static final String ADDRESS_REJECT = "address.reject";
    public static final String ADDRESS_REVOKE = "address.revoke";
    public static final String ADDRESS_DELETE = "address.delete";

    /* ---------------- 用户 ---------------- */

    public static final String USER_APPROVE = "user.approve";
    public static final String USER_REJECT = "user.reject";
    public static final String USER_DISABLE = "user.disable";
    public static final String USER_ENABLE = "user.enable";
    public static final String USER_DELETE = "user.delete";
    public static final String USER_PHONE = "user.phone";

    /* ---------------- 订单 ---------------- */

    public static final String ORDER_ACCEPT = "order.accept";
    public static final String ORDER_REJECT = "order.reject";
    public static final String ORDER_ADVANCE = "order.advance";
    public static final String ORDER_PICKUP = "order.pickup";
    public static final String ORDER_PAY_CONFIRM = "order.pay.confirm";
    public static final String ORDER_PAY_REVERT = "order.pay.revert";
    public static final String ORDER_REFUND = "order.refund";
    public static final String ORDER_CANCEL_APPROVE = "order.cancel.approve";
    public static final String ORDER_CANCEL_DENY = "order.cancel.deny";
    public static final String ORDER_CANCEL_AUTO = "order.cancel.auto";

    /** 筛选下拉用；顺序即页面展示顺序，按业务域成组 */
    private static final List<Action> ALL = List.of(
            new Action(ADDRESS_CREATE, "新增地址", TABLE_ADDRESS),
            new Action(ADDRESS_UPDATE, "修改地址", TABLE_ADDRESS),
            new Action(ADDRESS_APPROVE, "地址通过", TABLE_ADDRESS),
            new Action(ADDRESS_REJECT, "驳回地址", TABLE_ADDRESS),
            new Action(ADDRESS_REVOKE, "收回地址", TABLE_ADDRESS),
            new Action(ADDRESS_DELETE, "删除地址", TABLE_ADDRESS),
            new Action(USER_APPROVE, "用户通过", TABLE_USER),
            new Action(USER_REJECT, "驳回用户", TABLE_USER),
            new Action(USER_DISABLE, "停用用户", TABLE_USER),
            new Action(USER_ENABLE, "恢复用户", TABLE_USER),
            new Action(USER_DELETE, "删除用户", TABLE_USER),
            new Action(USER_PHONE, "修改手机号", TABLE_USER),
            new Action(ORDER_ACCEPT, "接单", TABLE_ORDER),
            new Action(ORDER_REJECT, "拒单", TABLE_ORDER),
            new Action(ORDER_ADVANCE, "推进状态", TABLE_ORDER),
            new Action(ORDER_PICKUP, "取餐核销", TABLE_ORDER),
            new Action(ORDER_PAY_CONFIRM, "确认收款", TABLE_ORDER),
            new Action(ORDER_PAY_REVERT, "收款回退", TABLE_ORDER),
            new Action(ORDER_REFUND, "标记退款", TABLE_ORDER),
            new Action(ORDER_CANCEL_APPROVE, "同意取消", TABLE_ORDER),
            new Action(ORDER_CANCEL_DENY, "驳回取消", TABLE_ORDER),
            new Action(ORDER_CANCEL_AUTO, "超时自动同意取消", TABLE_ORDER)
    );

    private AuditActions() {
    }

    public static List<Action> all() {
        return ALL;
    }

    /** 动作码的中文名；未知码原样返回，避免页面出现空白 */
    public static String label(String code) {
        for (Action action : ALL) {
            if (action.code().equals(code)) {
                return action.label();
            }
        }
        return code == null ? "" : code;
    }

    /**
     * @param code      动作码
     * @param label     中文名
     * @param tableName 归属对象表，用于「对象类型」与「动作」两个筛选联动
     */
    public record Action(String code, String label, String tableName) {
    }
}

package com.xxxfhf.meal.audit;

import java.util.List;

/**
 * 操作记录的动作码清单。
 *
 * <p>一句话区分「动作」和「对象」：{@code table_name} 说动的是哪类东西，
 * {@code action} 说具体做了什么。两者拼起来才是完整含义（{@code address} + {@code address.revoke}）。
 *
 * <p><b>不含订单域</b>：订单的状态流转改记 {@code order_status_log}（需求 4.20），
 * 那张表要的是结构化的 {@code from_status → to_status}，能上色、能算耗时；
 * 通用流水只能从中文句子里抠状态值。库里 {@code table_name = 'order'} 的历史行不删，
 * 但不会有新增，动作名也不再这里维护。
 *
 * <p><b>本清单同时是筛选下拉的数据源</b>（{@code GET /admin/audit-log/actions}）。
 * 新增一个埋点必须在这里加一项，否则页面上筛不到 —— 与权限码要同步加进角色页正则同类，
 * 都是「漏了不报错、只是安静地少一块」。
 */
public final class AuditActions {

    /* ---------------- 对象表 ---------------- */

    public static final String TABLE_ADDRESS = "address";
    public static final String TABLE_USER = "user";
    public static final String TABLE_INVITE = "invite_code";

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
    /** 指定为商家：把已有微信顾客就地升级为商家账号（需求 4.17），一次记录写明新账号 */
    public static final String USER_PROMOTE = "user.promote";
    public static final String USER_PHONE = "user.phone";
    /** 查看完整手机号：只读动作，记下来是为了「谁看过谁的号」可追溯 */
    public static final String USER_PHONE_VIEW = "user.phone.view";
    /** 用户在小程序里自己注销（不是商家删人）：删除或匿名化，记录里只留昵称与账号尾号 */
    public static final String USER_SELF_DELETE = "user.selfDelete";

    /* ---------------- 邀请码 ---------------- */

    /** 生成邀请码：单张与批量都记，批量时一张一行，事后能对上是哪次发的 */
    public static final String INVITE_CREATE = "invite.create";
    /** 作废邀请码：只作废未使用的码，记下来是为了「谁把哪张码收了」可追溯 */
    public static final String INVITE_REVOKE = "invite.revoke";

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
            new Action(USER_PROMOTE, "指定为商家", TABLE_USER),
            new Action(USER_PHONE, "修改手机号", TABLE_USER),
            new Action(USER_PHONE_VIEW, "查看完整手机号", TABLE_USER),
            new Action(USER_SELF_DELETE, "用户自助注销", TABLE_USER),
            new Action(INVITE_CREATE, "生成邀请码", TABLE_INVITE),
            new Action(INVITE_REVOKE, "作废邀请码", TABLE_INVITE)
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

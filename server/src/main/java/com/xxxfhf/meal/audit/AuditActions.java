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
    /** 菜品库（原料层） */
    public static final String TABLE_DISH = "dish";
    /** 盒饭（售卖层） */
    public static final String TABLE_COMBO = "combo";
    /** 规格组（含选项与差价） */
    public static final String TABLE_SPEC = "spec_group";
    /** 每日菜单（选品、份数、发布） */
    public static final String TABLE_MENU = "daily_menu";
    /** 配置项。对象主键是键名（{@code record_id} 记 0），摘要里带变化的键 */
    public static final String TABLE_CONFIG = "config";
    /** 后台角色与账号授权 */
    public static final String TABLE_ROLE = "role";
    /** 数据字典（类型与项） */
    public static final String TABLE_DICT = "dict";

    /* ---------------- 地址 ---------------- */

    public static final String ADDRESS_CREATE = "address.create";
    public static final String ADDRESS_UPDATE = "address.update";
    public static final String ADDRESS_APPROVE = "address.approve";
    public static final String ADDRESS_REJECT = "address.reject";
    public static final String ADDRESS_REVOKE = "address.revoke";
    public static final String ADDRESS_DELETE = "address.delete";
    /** 停用地址：不影响审核状态，只让它不可用于下单（N-44，见需求 4.7） */
    public static final String ADDRESS_DISABLE = "address.disable";
    /** 启用地址：连同停用原因一起清空 */
    public static final String ADDRESS_ENABLE = "address.enable";

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
    /** 商家重置商家账号密码（v1.53）：只记「改了谁的密码」，不记密码内容 */
    public static final String USER_PASSWORD = "user.password";

    /* ---------------- 邀请码 ---------------- */

    /** 生成邀请码：单张与批量都记，批量时一张一行，事后能对上是哪次发的 */
    public static final String INVITE_CREATE = "invite.create";
    /** 作废邀请码：只作废未使用的码，记下来是为了「谁把哪张码收了」可追溯 */
    public static final String INVITE_REVOKE = "invite.revoke";

    /* ---------------- 商品（菜品 / 盒饭 / 规格组） ---------------- */

    public static final String DISH_CREATE = "dish.create";
    public static final String DISH_UPDATE = "dish.update";
    /** 菜品启用 / 停用：不删只停是常态，必须能查是谁停的 */
    public static final String DISH_STATUS = "dish.status";
    public static final String DISH_DELETE = "dish.delete";

    public static final String COMBO_CREATE = "combo.create";
    public static final String COMBO_UPDATE = "combo.update";
    public static final String COMBO_STATUS = "combo.status";
    public static final String COMBO_DELETE = "combo.delete";

    public static final String SPEC_CREATE = "spec.create";
    public static final String SPEC_UPDATE = "spec.update";
    public static final String SPEC_STATUS = "spec.status";
    public static final String SPEC_DELETE = "spec.delete";

    /* ---------------- 每日菜单 ---------------- */

    /** 保存某供餐日的选品与份数；摘要带日期，便于按日期追「谁把份数改了」 */
    public static final String MENU_SAVE = "menu.save";
    public static final String MENU_PUBLISH = "menu.publish";
    /** 撤下菜单（`close`）：用户端当天那个 Tab 会消失，影响面比保存大 */
    public static final String MENU_CLOSE = "menu.close";

    /* ---------------- 配置 ---------------- */

    /** 保存配置：一次提交记一条，摘要只列**发生了变化**的键与前后值 */
    public static final String CONFIG_UPDATE = "config.update";

    /* ---------------- 角色与账号 ---------------- */

    public static final String ROLE_CREATE = "role.create";
    public static final String ROLE_UPDATE = "role.update";
    public static final String ROLE_DELETE = "role.delete";
    /** 给商家账号分配角色：权限变化的实际入口，比改角色定义更常见 */
    public static final String ROLE_ASSIGN = "role.assign";
    /** 解除登录锁定 */
    public static final String ROLE_UNLOCK = "role.unlock";

    /* ---------------- 数据字典 ---------------- */

    public static final String DICT_TYPE_SAVE = "dict.type.save";
    public static final String DICT_TYPE_DELETE = "dict.type.delete";
    public static final String DICT_ITEM_SAVE = "dict.item.save";
    public static final String DICT_ITEM_STATUS = "dict.item.status";
    public static final String DICT_ITEM_DELETE = "dict.item.delete";

    /** 筛选下拉用；顺序即页面展示顺序，按业务域成组 */
    private static final List<Action> ALL = List.of(
            new Action(ADDRESS_CREATE, "新增地址", TABLE_ADDRESS),
            new Action(ADDRESS_UPDATE, "修改地址", TABLE_ADDRESS),
            new Action(ADDRESS_APPROVE, "地址通过", TABLE_ADDRESS),
            new Action(ADDRESS_REJECT, "驳回地址", TABLE_ADDRESS),
            new Action(ADDRESS_REVOKE, "收回地址", TABLE_ADDRESS),
            new Action(ADDRESS_DELETE, "删除地址", TABLE_ADDRESS),
            new Action(ADDRESS_DISABLE, "停用地址", TABLE_ADDRESS),
            new Action(ADDRESS_ENABLE, "启用地址", TABLE_ADDRESS),
            new Action(USER_APPROVE, "用户通过", TABLE_USER),
            new Action(USER_REJECT, "驳回用户", TABLE_USER),
            new Action(USER_DISABLE, "停用用户", TABLE_USER),
            new Action(USER_ENABLE, "恢复用户", TABLE_USER),
            new Action(USER_DELETE, "删除用户", TABLE_USER),
            new Action(USER_PROMOTE, "指定为商家", TABLE_USER),
            new Action(USER_PHONE, "修改手机号", TABLE_USER),
            new Action(USER_PHONE_VIEW, "查看完整手机号", TABLE_USER),
            new Action(USER_SELF_DELETE, "用户自助注销", TABLE_USER),
            new Action(USER_PASSWORD, "重置商家密码", TABLE_USER),
            new Action(INVITE_CREATE, "生成邀请码", TABLE_INVITE),
            new Action(INVITE_REVOKE, "作废邀请码", TABLE_INVITE),
            new Action(DISH_CREATE, "新增菜品", TABLE_DISH),
            new Action(DISH_UPDATE, "修改菜品", TABLE_DISH),
            new Action(DISH_STATUS, "菜品启停用", TABLE_DISH),
            new Action(DISH_DELETE, "删除菜品", TABLE_DISH),
            new Action(COMBO_CREATE, "新增盒饭", TABLE_COMBO),
            new Action(COMBO_UPDATE, "修改盒饭", TABLE_COMBO),
            new Action(COMBO_STATUS, "盒饭上下架", TABLE_COMBO),
            new Action(COMBO_DELETE, "删除盒饭", TABLE_COMBO),
            new Action(SPEC_CREATE, "新增规格组", TABLE_SPEC),
            new Action(SPEC_UPDATE, "修改规格组", TABLE_SPEC),
            new Action(SPEC_STATUS, "规格组启停用", TABLE_SPEC),
            new Action(SPEC_DELETE, "删除规格组", TABLE_SPEC),
            new Action(MENU_SAVE, "保存每日菜单", TABLE_MENU),
            new Action(MENU_PUBLISH, "发布每日菜单", TABLE_MENU),
            new Action(MENU_CLOSE, "撤下每日菜单", TABLE_MENU),
            new Action(CONFIG_UPDATE, "修改配置", TABLE_CONFIG),
            new Action(ROLE_CREATE, "新增角色", TABLE_ROLE),
            new Action(ROLE_UPDATE, "修改角色", TABLE_ROLE),
            new Action(ROLE_DELETE, "删除角色", TABLE_ROLE),
            new Action(ROLE_ASSIGN, "分配角色", TABLE_ROLE),
            new Action(ROLE_UNLOCK, "解除登录锁定", TABLE_ROLE),
            new Action(DICT_TYPE_SAVE, "保存字典类型", TABLE_DICT),
            new Action(DICT_TYPE_DELETE, "删除字典类型", TABLE_DICT),
            new Action(DICT_ITEM_SAVE, "保存字典项", TABLE_DICT),
            new Action(DICT_ITEM_STATUS, "字典项启停用", TABLE_DICT),
            new Action(DICT_ITEM_DELETE, "删除字典项", TABLE_DICT)
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
     * 对象表的中文名。**这里是唯一一份**，{@code AuditService.tableLabel} 也调它 ——
     * 两处各写一份 switch，早晚会出现「流水里显示盒饭、筛选里叫商品」这种错位。
     */
    private static final java.util.Map<String, String> TABLE_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry(TABLE_ADDRESS, "地址"),
            java.util.Map.entry(TABLE_USER, "用户"),
            java.util.Map.entry(TABLE_INVITE, "邀请码"),
            java.util.Map.entry(TABLE_DISH, "菜品"),
            java.util.Map.entry(TABLE_COMBO, "盒饭"),
            java.util.Map.entry(TABLE_SPEC, "规格组"),
            java.util.Map.entry(TABLE_MENU, "每日菜单"),
            java.util.Map.entry(TABLE_CONFIG, "配置"),
            java.util.Map.entry(TABLE_ROLE, "角色"),
            java.util.Map.entry(TABLE_DICT, "数据字典")
    );

    /** 对象表中文名；未登记的原样返回，避免页面出现空白 */
    public static String tableLabelOf(String tableName) {
        return tableName == null ? "" : TABLE_LABELS.getOrDefault(tableName, tableName);
    }

    /**
     * @param code      动作码
     * @param label     中文名
     * @param tableName 归属对象表，用于「对象类型」与「动作」两个筛选联动
     */
    public record Action(String code, String label, String tableName, String tableNameLabel) {

        /**
         * 只给三段的构造：{@code tableNameLabel} 由 {@link #tableLabelOf} 补齐。
         * 下面那张 ALL 清单因此不必逐行重复写中文名，也就不会出现「改了表名忘了改文案」。
         */
        public Action(String code, String label, String tableName) {
            this(code, label, tableName, tableLabelOf(tableName));
        }
    }
}

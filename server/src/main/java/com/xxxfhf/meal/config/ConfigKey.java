package com.xxxfhf.meal.config;

/**
 * 配置项键名，与 deploy/mysql/init/02-data.sql 的初始数据一一对应。
 *
 * 配置值统一存 TEXT，JSON 类配置（数组 / 对象）在读取时解析。
 * 键名一旦投入使用不要改，改键等于丢配置。
 */
public final class ConfigKey {

    private ConfigKey() {
    }

    /** 截单时间 HH:mm，作用于供餐日的前一天 */
    public static final String CUTOFF_TIME = "cutoffTime";
    /** 供餐时段承诺文案，如「午饭前」 */
    public static final String SERVE_TIME_TEXT = "serveTimeText";
    /** 自取可选时段，JSON 数组 */
    public static final String PICKUP_SLOTS = "pickupSlots";
    /** 配送费（分） */
    public static final String DELIVERY_FEE = "deliveryFee";
    public static final String SHOP_NAME = "shopName";
    public static final String SHOP_NOTICE = "shopNotice";
    /**
     * 店铺地址（自取点）。自取单在下单成功页、订单详情与「可以取餐了」推送里展示。
     * 留空表示商家还没填，前端不渲染该行，不显示「未设置」。
     */
    public static final String SHOP_ADDRESS = "shopAddress";
    /**
     * 商家联系电话。用户端首页与订单详情提供「拨打电话」。
     *
     * 前置需求里反复出现「转账请联系商家获取收款账号」「请联系商家添加地址」，
     * 若小程序内没有拨号入口，这些话全都落不了地。
     */
    public static final String SHOP_PHONE = "shopPhone";
    /** 未付款订单释放份数的等待分钟数 */
    public static final String PREPAY_RELEASE_MINUTES = "prepayReleaseMinutes";
    /**
     * 取消申请超时自动同意的等待小时数。
     *
     * 0 表示关闭自动同意。设这个兜底是因为：申请悬挂时订单卡在待接单，
     * 份数一直被占着，商家忘了批就等于这份盒饭永远卖不出去。
     */
    public static final String CANCEL_REQUEST_TIMEOUT_HOURS = "cancelRequestTimeoutHours";
    /** 停业的供餐日列表，JSON 数组，如 ["2026-12-01"] */
    public static final String CLOSED_DATES = "closedDates";
    /**
     * 允许进入本店的微信群 openGId 白名单，JSON 数组。
     *
     * 准入校验只认这里登记过的群：群卡片被转发到别的群，解出的 openGId 不在此列表即拒绝。
     * 空数组表示「还没有登记任何群」，此时只允许邀请码进入。
     */
    public static final String GROUP_OPEN_IDS = "groupOpenIds";
    /**
     * 订阅消息模板配置，JSON 对象。键为通知类型（see NotifyType.code），值为
     * { templateId, page, fields }。
     *
     * 默认空对象 {} —— 模板 ID 必须去小程序后台申请（F-I01），没申请到之前
     * 系统照常运行，只是不发通知，不会报错。
     */
    public static final String SUBSCRIBE_TEMPLATES = "subscribeTemplates";

    /* ---------------- 业务限制（商家可在后台调整，即时生效） ---------------- */

    /**
     * 一单最多几种盒饭。
     *
     * 这三个份数上限是**互为独立**的三道闸，不是一道闸的三种写法：
     * 「最多几种」管购物车里有多少行，「每种最多几份」管单行数量，
     * 「一单总份数」管所有行加起来 —— 只配其中一两个都会留下敞口。
     */
    public static final String ORDER_MAX_ITEM_KINDS = "orderMaxItemKinds";
    public static final int ORDER_MAX_ITEM_KINDS_DEFAULT = 4;

    /** 单个盒饭一次最多几份 */
    public static final String ORDER_MAX_QTY_PER_ITEM = "orderMaxQtyPerItem";
    public static final int ORDER_MAX_QTY_PER_ITEM_DEFAULT = 5;

    /**
     * 一单总份数上限（所有明细份数之和）。
     *
     * 代客下单不受此项与 {@link #ORDER_MAX_QTY_PER_ITEM}、{@link #ORDER_MAX_ITEM_KINDS} 约束：
     * 团餐场景一次几十份是正常需求，数量由商家对着电话自己把关，系统只兜住当日剩余份数。
     */
    public static final String ORDER_MAX_QTY_TOTAL = "orderMaxQtyTotal";
    public static final int ORDER_MAX_QTY_TOTAL_DEFAULT = 20;

    /** 盒饭里单个菜品的份数上限（例汤 2 份是常态，这里防的是误填 200 份） */
    public static final String COMBO_DISH_MAX_QTY = "comboDishMaxQty";
    public static final int COMBO_DISH_MAX_QTY_DEFAULT = 99;

    /** 一次批量接单 / 批量拒单的订单数上限，两个入口共用 */
    public static final String ORDER_BATCH_LIMIT = "orderBatchLimit";
    public static final int ORDER_BATCH_LIMIT_DEFAULT = 20;

    /** 邀请码有效期上限（小时）。720 = 30 天，防「永不过期」的码在群里长期流传 */
    public static final String INVITE_MAX_EXPIRE_HOURS = "inviteMaxExpireHours";
    public static final int INVITE_MAX_EXPIRE_HOURS_DEFAULT = 24 * 30;
}

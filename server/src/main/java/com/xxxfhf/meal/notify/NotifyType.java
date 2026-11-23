package com.xxxfhf.meal.notify;

/**
 * 订阅消息与站内消息的类型清单，与需求 4.12「消息通知」一一对应。
 *
 * <p>其中 {@code completed} / {@code cancelRejected} / {@code refunded} / {@code stockReleased}
 * 四类**只落站内消息、不发微信订阅消息**（集合见 {@code NotifyService.MESSAGE_ONLY}）。
 *
 * 不做「截单前 N 小时提醒」：订阅消息是一次性的，每推一条消耗一次授权，
 * 提醒类消息最容易把授权次数耗光，反而让「拒单」这种关键通知发不出去。
 *
 * code 同时是配置项 subscribeTemplates 里的键名，改 code 等于丢模板配置。
 */
public enum NotifyType {

    /** 用户下单成功 → 商家 */
    NEW_ORDER("newOrder", "新订单提醒", Recipient.MERCHANT),

    /** 商家接单 → 用户 */
    ACCEPTED("accepted", "订单已接单", Recipient.USER),

    /** 商家拒单 → 用户（必须带原因） */
    REJECTED("rejected", "订单被拒", Recipient.USER),

    /** 订单可取餐 → 用户（自取单：带取餐码） */
    READY("ready", "可以取餐了", Recipient.USER),

    /**
     * 订单出餐、开始配送 → 用户（配送单）。
     *
     * 与 {@link #READY} 是同一个订单状态，但意思完全不同：自取是「来拿」，配送是「等着收货」。
     * 硬套取餐模板等于告诉配送用户「自己去取」，所以拆成两套模板。
     */
    DELIVERED("delivered", "订单配送中", Recipient.USER),

    /**
     * 订单结束 → 用户（配送单「已送达」，自取单「已取餐」）。
     *
     * <p><b>只写站内消息，不走微信推送</b>：用户侧一次性订阅一次最多授权 3 个模板，
     * 接单 / 拒单 / 出餐（配送单是「已出餐配送」）已经占满，这条没有额度可用。
     * 跳过逻辑在 {@code NotifyService.doFire}，见那里的注释。
     */
    COMPLETED("completed", "订单已完成", Recipient.USER),

    /** 用户提交取消申请（截单后）→ 商家。不推的话申请只会躺在订单列表里等人翻 */
    CANCEL_REQUEST("cancelRequest", "用户申请取消", Recipient.MERCHANT),

    /** 用户取消（截单前自撤、或取消申请被同意）→ 商家 */
    CANCELLED("cancelled", "订单已取消", Recipient.MERCHANT),

    /* ---------------- 只落站内消息的三类（v1.53 / 需求 4.12 / F-I04） ---------------- */

    /**
     * 取消申请被驳回 → 用户。
     *
     * <p>为什么不推送：微信一次性订阅一次最多授权 3 个模板，接单 / 拒单 / 出餐已经占满，
     * 第 4 条根本发不出去。这三类都不影响「今天吃什么」，用户进小程序时看到即可。
     */
    CANCEL_REJECTED("cancelRejected", "取消申请未通过", Recipient.USER),

    /** 商家标记退款完成 → 用户（带实际退款金额与备注） */
    REFUNDED("refunded", "退款已完成", Recipient.USER),

    /** 份数因超时未申报支付方式被释放 → 用户（订单还在，但那份可能被别人买走） */
    STOCK_RELEASED("stockReleased", "份数已释放", Recipient.USER);

    /** 接收方 */
    public enum Recipient {
        /** 下单用户本人 */
        USER,
        /** 已绑定通知微信的商家账号，可能多个 */
        MERCHANT
    }

    private final String code;
    private final String label;
    private final Recipient recipient;

    NotifyType(String code, String label, Recipient recipient) {
        this.code = code;
        this.label = label;
        this.recipient = recipient;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public Recipient recipient() {
        return recipient;
    }
}

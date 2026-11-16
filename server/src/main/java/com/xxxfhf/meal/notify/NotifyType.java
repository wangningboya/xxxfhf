package com.xxxfhf.meal.notify;

/**
 * 五类订阅消息，与需求 4.12「消息通知」一一对应。
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

    /** 订单可取餐 / 已送达 → 用户 */
    READY("ready", "可以取餐了", Recipient.USER),

    /** 用户取消（截单前自撤、或取消申请被同意）→ 商家 */
    CANCELLED("cancelled", "订单已取消", Recipient.MERCHANT);

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

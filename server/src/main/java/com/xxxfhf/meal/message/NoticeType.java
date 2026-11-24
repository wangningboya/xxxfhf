package com.xxxfhf.meal.message;

/**
 * **不挂订单**的站内消息类型（v3.50 / 需求 4.12「非订单类」）。
 *
 * <p>为什么单独一个枚举，而不是塞进 {@code NotifyType}：
 * {@code NotifyType} 同时驱动「站内消息 + 微信订阅消息」，它的标题与正文从订单字段现算
 * （{@code messageTitle(type, order)}），switch 是全量穷举的。审核、地址这类消息没有订单，
 * 硬塞进去会让每个 switch 都多出几个永远走不到的分支。
 *
 * <p>这四类**只落站内、不发订阅消息**：一次性订阅要用户当场授权，审核发生在几小时后，
 * 没有可用的授权次数；它们也都不影响「今天吃什么」，进小程序看得到即可。
 *
 * <p>{@code code} 写进 {@code user_message.msg_type}，与 {@code NotifyType.code} 共用同一列；
 * 两端按 code 取图标与标签，未知 code 回落默认样式（不报错）。
 */
public enum NoticeType {

    /** 审核通过 → 用户。商家点了通过而用户不知道，这个人就不会来 */
    USER_APPROVED("userApproved", "审核已通过"),

    /** 审核驳回 → 用户（必须带原因，原因原样进正文） */
    USER_REJECTED("userRejected", "审核未通过"),

    /** 地址被驳回 → 归属用户（带原因） */
    ADDRESS_REJECTED("addressRejected", "地址未通过"),

    /** 地址被收回（active → pending）或被停用 → 归属用户 */
    ADDRESS_REVOKED("addressRevoked", "地址已停用");

    private final String code;
    private final String label;

    NoticeType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}

package com.xxxfhf.meal.user;

/**
 * 用户审核状态。与《需求文档》第二章的一致。
 */
public final class AuditStatus {

    private AuditStatus() {
    }

    /** 已进入、待商家审核：只能浏览，不能下单 */
    public static final String PENDING = "pending";
    /** 审核通过，可下单 */
    public static final String APPROVED = "approved";
    /** 驳回，需联系群管理员 */
    public static final String REJECTED = "rejected";
    /** 被禁用 */
    public static final String DISABLED = "disabled";
}

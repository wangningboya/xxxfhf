package com.xxxfhf.meal.invite;

/**
 * 邀请码状态。
 *
 * used / expired / revoked 都是终态：一次性使用，用掉、过期或作废即失效，不回收、不复活。
 * 作废与过期的区别只在原因：作废是商家主动撤销（发错群、发错人），过期是时间到了。
 */
public final class InviteStatus {

    private InviteStatus() {
    }

    /** 未使用，可核销 */
    public static final String UNUSED = "unused";
    /** 已被使用，记录 usedBy / usedAt */
    public static final String USED = "used";
    /** 已过期（过期由 expireAt 判定，本状态是懒标记，便于后台筛出） */
    public static final String EXPIRED = "expired";
    /** 商家主动作废（发错群、发错人）。未使用的码才能作废 */
    public static final String REVOKED = "revoked";
}

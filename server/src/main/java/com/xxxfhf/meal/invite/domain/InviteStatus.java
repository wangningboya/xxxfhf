package com.xxxfhf.meal.invite.domain;

/**
 * 邀请码状态。
 *
 * used / expired / revoked 都是终态：次数用尽、过期或作废即不能再核销，不回收、不复活。
 * 作废与过期的区别只在原因：作废是商家主动撤销（发错群、发错人），过期是时间到了。
 */
public final class InviteStatus {

    private InviteStatus() {
    }

    /** 未使用（次数一次没动过），可核销 */
    public static final String UNUSED = "unused";
    /** 部分使用：用过但还没扣完次数，仍可核销 */
    public static final String PARTIAL = "partial";
    /** 已用完：次数扣到 0 */
    public static final String USED = "used";
    /** 已过期（由 expireAt 判定，本状态是懒标记，便于后台筛出） */
    public static final String EXPIRED = "expired";
    /** 商家主动作废（发错群、发错人）。还有剩余次数的码才能作废 */
    public static final String REVOKED = "revoked";

    /** 还能继续核销的状态 */
    public static boolean consumable(String status) {
        return UNUSED.equals(status) || PARTIAL.equals(status);
    }
}

package com.xxxfhf.meal.invite;

/**
 * 邀请码状态。
 *
 * used 与 expired 都是终态：一次性使用，用掉或过期即失效，不回收、不复活。
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
}

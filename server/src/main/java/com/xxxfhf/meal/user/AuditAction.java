package com.xxxfhf.meal.user;

/**
 * 审核动作。
 *
 * 允许的状态流转（见 {@link UserAuditService}）：
 * - approve：pending / rejected → approved
 * - reject ：pending / rejected → rejected（原因必填）
 * - disable：pending / approved / rejected → disabled
 * - enable ：disabled → approved
 *
 * 注意 approved 不能再 reject：已经能下单的人被「驳回」会让历史订单语义混乱，
 * 要收回权限请用 disable，语义清晰且可恢复。
 */
public final class AuditAction {

    private AuditAction() {
    }

    public static final String APPROVE = "approve";
    public static final String REJECT = "reject";
    public static final String DISABLE = "disable";
    public static final String ENABLE = "enable";
}

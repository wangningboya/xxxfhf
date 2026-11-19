package com.xxxfhf.meal.order;

import java.time.LocalDateTime;

/**
 * 取消申请。存在 `order`.`cancel_request`。
 *
 * 只用于**截单后**的取消：此时用户已不能自助取消，要商家批。
 * 截单前的自助取消直接把订单置 cancelled，不经过这里。
 *
 * @param result approved / rejected / auto_approved（超时自动同意）；未处理时为 null
 * @param remark 处理备注。驳回时必须写原因，用户端会展示；自动同意时写明是系统处理
 */
public record CancelRequest(String reason, LocalDateTime appliedAt,
                            LocalDateTime handledAt, String result, Long handledBy,
                            String remark) {

    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    /** 超过配置时长商家仍未处理，系统按同意处理（见 CancelRequestTimeoutTask） */
    public static final String AUTO_APPROVED = "auto_approved";
}

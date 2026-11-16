package com.xxxfhf.meal.order;

import java.time.LocalDateTime;

/**
 * 取消申请。存在 `order`.`cancel_request`。
 *
 * 只用于**截单后**的取消：此时用户已不能自助取消，要商家批。
 * 截单前的自助取消直接把订单置 cancelled，不经过这里。
 *
 * @param result approved / rejected；未处理时为 null
 */
public record CancelRequest(String reason, LocalDateTime appliedAt,
                            LocalDateTime handledAt, String result, String handledBy) {

    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
}

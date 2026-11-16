package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.UserType;

import java.util.Collections;
import java.util.Set;

/**
 * 当前请求的登录主体。
 *
 * @param userId      用户主键
 * @param userType    customer（小程序群成员） / merchant（商家网页后台），取自库而不是令牌
 * @param auditStatus 审核状态，下单与审核判断都读这里而不是读 token，保证审核通过即时生效
 * @param permissions 由角色解析出的权限项集合
 */
public record CurrentUser(String userId, String userType, String auditStatus, Set<String> permissions) {

    public boolean isMerchant() {
        return UserType.MERCHANT.equals(userType);
    }

    public boolean isApproved() {
        return AuditStatus.APPROVED.equals(auditStatus);
    }

    /** 权限集合一律拷贝为不可变，避免调用方误改。 */
    public CurrentUser {
        permissions = permissions == null ? Collections.emptySet() : Set.copyOf(permissions);
    }
}

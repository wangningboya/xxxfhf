package com.xxxfhf.meal.invite.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 邀请码使用明细：一个码对应多人，一人一条。
 *
 * <p>存在这张表而不是往 {@code invite_code} 上加字段，是因为「一个码被 20 个人用」没法用一列表达；
 * 登录判准入（{@code hasConsumed}）也要按 {@code user_id} 反查，单表计数做不到。
 */
@TableName("invite_use")
public class InviteUse {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inviteCodeId;

    /** 使用者 userId；用户被删除后保留原值，后台显示「已删除用户 #id」 */
    private Long userId;

    private LocalDateTime usedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInviteCodeId() {
        return inviteCodeId;
    }

    public void setInviteCodeId(Long inviteCodeId) {
        this.inviteCodeId = inviteCodeId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }
}

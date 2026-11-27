package com.xxxfhf.meal.invite.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 邀请码。**一个码可被多人各用一次** + 有效期双重限制（见《需求文档》3.1 邀请码兜底）。
 *
 * <p>「谁用过」不再记在本表上：一张码对应多人，明细在 {@link InviteUse}。
 * 本表只留 {@code usedCount} 这个计数，核销时靠它判断还有没有剩余次数。
 */
@TableName("invite_code")
public class InviteCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 6 位数字，含前导 0（存字符串，避免 012345 变成 12345） */
    private String code;

    /** unused / partial / used / expired / revoked，见 InviteStatus */
    private String status;

    /** 可用次数：生成时定，正整数，不设上限 */
    private Integer maxUses;

    /** 已用次数 */
    private Integer usedCount;

    private LocalDateTime expireAt;

    /** 最近一次核销时间；一次没用过时为 null */
    private LocalDateTime lastUsedAt;

    private Long createdBy;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(Integer maxUses) {
        this.maxUses = maxUses;
    }

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

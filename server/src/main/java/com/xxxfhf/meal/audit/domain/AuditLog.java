package com.xxxfhf.meal.audit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 操作记录。对应表 {@code audit_log}。
 *
 * <p>追加型：只 insert，不改不删。所以它不继承 {@link AuditEntity} ——
 * 一行写进去就不会再变，再挂 created/updated 两套时间只会让人以为它能改。
 *
 * <p>{@code operatorName} 是**写入当时的快照**，故意冗余：账号改名甚至被删之后，
 * 历史记录仍然能读出「当时是谁」。
 */
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tableName;

    private Long recordId;

    private String action;

    /** 人读的一句话，含关键字段的前后值，如「驳回地址：3 栋没有 2 单元」 */
    private String summary;

    private Long operatorId;

    private String operatorName;

    /** admin / miniprogram / task */
    private String source;

    private LocalDateTime createdAt;

    /* ---------------- getters / setters ---------------- */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

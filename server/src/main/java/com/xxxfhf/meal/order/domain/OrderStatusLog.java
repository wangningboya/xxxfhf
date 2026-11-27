package com.xxxfhf.meal.order.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 订单状态流转记录。对应表 {@code order_status_log}（需求 4.20）。
 *
 * <p>追加型：只 insert，不改不删。所以它不继承 {@code AuditEntity} ——
 * 写了就不再变，挂 created/updated 两套时间只会让人以为它能改。
 *
 * <p>与 {@code audit_log} 的分工：那张表回答「运维追责」，只存一句话；
 * 本表回答「这一单经历过什么」，{@code fromStatus} → {@code toStatus} 是结构化存值，
 * 前端才能按状态上色、按节点算耗时。
 *
 * <p>{@code operatorName} 是<b>写入当时的快照</b>，故意冗余：账号改名甚至被删之后，
 * 历史记录仍读得出「当时是谁」。
 */
@TableName("order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** order 订单状态线 / pay 收款状态线 / event 业务事件 */
    private String logType;

    /** 动作码，见 {@link OrderLogActions} */
    private String action;

    /** 变更前存值；下单这类没有前值的为空 */
    private String fromStatus;

    /** 变更后存值；事件类为空 */
    private String toStatus;

    /** 补充说明：拒单原因、取消原因、释放原因等 */
    private String remark;

    /**
     * 收款方式快照，见 {@link PayMethod}。
     *
     * <p>只有 {@code pay.claim}（用户申报）与 {@code pay.confirm}（商家确认）两条有值，
     * 两条不一致即为「用户说微信、实际收现金」这类差异的书面依据。
     */
    private String payMethod;

    private Long operatorId;

    /** 操作人当时的名称快照 */
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

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getLogType() {
        return logType;
    }

    public void setLogType(String logType) {
        this.logType = logType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getPayMethod() {
        return payMethod;
    }

    public void setPayMethod(String payMethod) {
        this.payMethod = payMethod;
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

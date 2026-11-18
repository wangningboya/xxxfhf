package com.xxxfhf.meal.audit;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * 审计四列基类。需要「谁建的、谁改的」的表继承它即可。
 *
 * <p>本轮只给 {@code address} 铺（N-27 试点），其余 12 张表下一轮批量继承。
 *
 * <p>三条约定：
 * <ul>
 *   <li><b>主键不放进基类</b>：{@code config} 的主键是 {@code config_key}、{@code dict_type} 用 INPUT 策略，<br>
 *       统一继承主键会和现有实体打架。主键各表自己声明。</li>
 *   <li><b>{@code createdBy} / {@code updatedBy} 存 user_id</b>，不存用户名：改名不该让历史记录对不上人。<br>
 *       无登录态的定时任务写 {@code system}（见 {@link AuditHelper#actor()}）。</li>
 *   <li><b>对前端隐藏</b>：小程序不该知道是谁改的地址；后台要看时间就走各自的 Row 投影，<br>
 *       不要把实体直接当响应体。</li>
 * </ul>
 *
 * <p>写入靠 {@link AuditMetaObjectHandler} 自动填充。注意它**只对传了实体的
 * {@code insert(entity)} / {@code updateById(entity)} 生效**；
 * {@code update(null, lambdaUpdate())} 这类写法不会触发，必须补 {@link AuditHelper#stamp}。
 */
public abstract class AuditEntity {

    @TableField(fill = FieldFill.INSERT)
    @JsonIgnore
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    @JsonIgnore
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonIgnore
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonIgnore
    private String updatedBy;

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}

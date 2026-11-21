package com.xxxfhf.meal.address;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xxxfhf.meal.audit.AuditEntity;

/**
 * 收货地址。**必须挂一个区域**，不允许存在无区域地址（需求 4.7）。
 *
 * 与常规电商最大的不同：主体由**商家维护**，用户自己新增的地址要等商家确认才能选用。
 * 自取单不涉及地址，也就不涉及区域。
 *
 * 继承 {@link AuditEntity}：除「谁建的、谁改的」留痕外，created_at 还是待确认队列的
 * 排序依据（先来先审），所以它同时是业务字段（N-27）。
 */
@TableName("address")
public class Address extends AuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 所属区域，必填 */
    private Long regionId;

    /** 区域名称冗余，列表展示与选择器直接用，省一次 join */
    private String regionName;

    private String contactName;

    private String phone;

    /** 详细地址（区域内门牌，如「3 栋 2 单元 501」） */
    private String detail;

    /** 家 / 公司，可空 */
    private String tag;

    /** merchant 商家录入 / user 用户提交 */
    private String source;

    /** active 可用 / pending 待商家确认 / rejected 商家驳回 */
    private String status;

    /** 驳回原因，仅 status = rejected 时有值，会展示给用户 */
    private String rejectReason;

    /**
     * 启用状态，默认启用。
     *
     * <p>与 {@link #status} **正交**：status 管「商家审没审过」，这一列管「商家让不让用」。
     * 商家可以停用一条已经审过的地址（比如那栋楼装修送不进去），也可以在任何时候启用回来。
     * 两者的区别在用户端看得很清楚：驳回要用户改地址重新提交，停用不需要改任何内容。
     *
     * <p>区域停用**不写这一列**（区域停用时地址表一行都不动），可用性是判定时相乘出来的，
     * 见 {@code AddressService#usability}。
     */
    private Boolean enabled;

    /** 停用原因，仅 enabled = false 时可能非空，会展示给用户 */
    private String disabledReason;

    /* ---------------- getters / setters ---------------- */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getRegionId() {
        return regionId;
    }

    public void setRegionId(Long regionId) {
        this.regionId = regionId;
    }

    public String getRegionName() {
        return regionName;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public void setDisabledReason(String disabledReason) {
        this.disabledReason = disabledReason;
    }
}

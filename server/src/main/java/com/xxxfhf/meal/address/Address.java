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
}

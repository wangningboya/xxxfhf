package com.xxxfhf.meal.address;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 收货地址。**必须挂一个区域**，不允许存在无区域地址（需求 4.7）。
 *
 * 与常规电商最大的不同：主体由**商家维护**，用户自己新增的地址要等商家确认才能选用。
 * 自取单不涉及地址，也就不涉及区域。
 */
@TableName("address")
public class Address {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    /** 所属区域，必填 */
    private String regionId;

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

    /** active 可用 / pending 待商家确认 */
    private String status;

    /* ---------------- getters / setters ---------------- */

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRegionId() {
        return regionId;
    }

    public void setRegionId(String regionId) {
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
}

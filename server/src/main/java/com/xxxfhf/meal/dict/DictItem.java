package com.xxxfhf.meal.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 字典项。`item_value` 是写进业务表的值，`item_label` 只用于展示。
 *
 * 区分这两者是字典能用的前提：把「待接单」存进 order.status，
 * 之后改文案就得改数据。所以展示名可改，存值不可改。
 */
@TableName("dict_item")
public class DictItem {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String typeCode;

    private String itemValue;

    private String itemLabel;

    /** 前端配色：info / success / warning / danger / primary */
    private String tagType;

    private Integer sort;

    /** active 启用 / disabled 停用 */
    private String status;

    /** 1 内置项，不可删除、不可改存值 */
    private Boolean builtin;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getItemValue() {
        return itemValue;
    }

    public void setItemValue(String itemValue) {
        this.itemValue = itemValue;
    }

    public String getItemLabel() {
        return itemLabel;
    }

    public void setItemLabel(String itemLabel) {
        this.itemLabel = itemLabel;
    }

    public String getTagType() {
        return tagType;
    }

    public void setTagType(String tagType) {
        this.tagType = tagType;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Boolean builtin) {
        this.builtin = builtin;
    }
}

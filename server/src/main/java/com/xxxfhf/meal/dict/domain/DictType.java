package com.xxxfhf.meal.dict.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 字典类型。`type_code` 是代码里的取值键，**一旦被代码引用就不能改**。
 *
 * 内置类型（`builtin = 1`）的类型编码与项都不允许增删，
 * 只能改显示名、颜色、排序、启停 —— 见 DictService 的约束说明。
 */
@TableName("dict_type")
public class DictType {

    @TableId(type = IdType.INPUT)
    private String typeCode;

    private String typeName;

    /** 1 内置，0 自定义 */
    private Boolean builtin;

    private String remark;

    private LocalDateTime createdAt;

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public Boolean getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Boolean builtin) {
        this.builtin = builtin;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

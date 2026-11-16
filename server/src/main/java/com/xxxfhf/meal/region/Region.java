package com.xxxfhf.meal.region;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 区域：商家创建，区域下才是用户的详细地址（两级结构）。
 *
 * 区域的作用是给备菜与配送分单做归集 —— 备菜区域汇总按订单快照里的区域分组，
 * 自取单没有区域，单独一栏。
 */
@TableName("region")
public class Region {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 区域名称，唯一。同名区域不允许重复创建 */
    private String name;

    /** 备注，如「东门进，找 3 栋」 */
    private String remark;

    private Integer sort;

    /** enabled / disabled */
    private String status;

    private LocalDateTime createdAt;

    /* ---------------- getters / setters ---------------- */

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

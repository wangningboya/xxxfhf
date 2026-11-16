package com.xxxfhf.meal.goods;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 菜品（原料层）。
 *
 * **没有价格字段**：一个盒饭一个价，与含几个菜无关，价格挂在 {@link Combo#getPrice()} 上。
 * 菜品只回答「这盒饭里有什么」，不单独售卖。
 */
@TableName("dish")
public class Dish {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 菜名，如「红烧肉」 */
    private String name;

    /** 图片地址，可选 */
    private String image;

    /** 预留分类字段。本版不提供维护入口，也不参与前端分组展示（需求 4.3） */
    private String categoryId;

    /** enabled / disabled，见 GoodsStatus */
    private String status;

    private Integer sort;

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

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}

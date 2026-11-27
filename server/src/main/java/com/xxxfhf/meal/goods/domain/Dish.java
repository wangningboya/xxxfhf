package com.xxxfhf.meal.goods.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 菜品（原料层）。
 *
 * **没有价格字段**：一个盒饭一个价，与含几个菜无关，价格挂在 {@link Combo#getPrice()} 上。
 * 菜品只回答「这盒饭里有什么」，不单独售卖。
 *
 * **没有图片字段**：菜品不配图，照片只属于盒饭封面（{@link Combo#getCover()}）。
 */
@TableName("dish")
public class Dish {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 菜名，如「红烧肉」 */
    private String name;

    /**
     * 原料分类存值，见字典 {@code dish_category}（自定义类型，本期不预置项、不做界面）。
     *
     * <p>与「商品分类」是两个正交维度：这里只是把菜归类（将来做备菜分组用），
     * **不影响盒饭组成与售卖**。存值口径与 {@link Combo#getCategoryCode()} 一致。
     */
    private String categoryCode;

    /** enabled / disabled，见 GoodsStatus */
    private String status;

    private Integer sort;

    /* ---------------- getters / setters ---------------- */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
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

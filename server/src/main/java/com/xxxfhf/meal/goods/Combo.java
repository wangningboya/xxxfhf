package com.xxxfhf.meal.goods;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.util.List;

/**
 * 盒饭（售卖层）：用户真正下单买的东西。
 *
 * 两个 JSON 列需要 autoResultMap = true 才能读回，漏了会安静地读出 null：
 * - {@code dishes}：组成菜品与每菜份数，备菜统计的唯一依据
 * - {@code specGroupIds}：启用的规格组，空数组表示这份盒饭没有规格
 *
 * `desc` 是 MySQL 关键字，@TableField 里必须带反引号，否则生成的 SQL 在这一列上语法错误。
 */
@TableName(value = "combo", autoResultMap = true)
public class Combo {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 盒饭名称，如「A 餐」 */
    private String name;

    private String cover;

    @TableField("`desc`")
    private String desc;

    /** 整份定价，单位**分**。与含几个菜无关，不做菜品价格累加 */
    private Integer price;

    /** 组成菜品 [{dishId,name,qty}]，菜名是快照 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<ComboDish> dishes;

    /** 启用的规格组 ID 列表；空数组 = 无规格 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> specGroupIds;

    /** on / off，见 GoodsStatus */
    private String status;

    /** 预留分类，本版不参与展示分组 */
    private String categoryId;

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

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public Integer getPrice() {
        return price;
    }

    public void setPrice(Integer price) {
        this.price = price;
    }

    public List<ComboDish> getDishes() {
        return dishes;
    }

    public void setDishes(List<ComboDish> dishes) {
        this.dishes = dishes;
    }

    public List<String> getSpecGroupIds() {
        return specGroupIds;
    }

    public void setSpecGroupIds(List<String> specGroupIds) {
        this.specGroupIds = specGroupIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}

package com.xxxfhf.meal.goods.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规格组：一组互斥的选项，用来表达「同类替换」（白米饭 ↔ 杂粮饭）。
 *
 * 当前只有「主食」一组，但结构是通用的 —— 以后加「辣度」「加料」只加数据，不改代码。
 *
 * `required` 是 MySQL 关键字，列名必须带反引号。
 */
@TableName(value = "spec_group", autoResultMap = true)
public class SpecGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 组名，如「主食」 */
    private String name;

    /** 是否必选。必选组启用后，用户下单必须选定一项 */
    @TableField("`required`")
    private Boolean required;

    /**
     * 是否计入备菜。主食组为 true，辣度类为 false。
     *
     * 少了这个标记，以后加「微辣 / 中辣」时这些选项会跑进备菜表变成垃圾数据。
     */
    private Boolean countInPrep;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<SpecOption> options;

    private Integer sort;

    /** enabled / disabled，见 GoodsStatus */
    private String status;

    private LocalDateTime createdAt;

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

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Boolean getCountInPrep() {
        return countInPrep;
    }

    public void setCountInPrep(Boolean countInPrep) {
        this.countInPrep = countInPrep;
    }

    public List<SpecOption> getOptions() {
        return options;
    }

    public void setOptions(List<SpecOption> options) {
        this.options = options;
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

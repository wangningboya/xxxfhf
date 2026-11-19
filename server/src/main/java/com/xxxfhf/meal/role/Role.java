package com.xxxfhf.meal.role;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色（RBAC）。内置「商家」角色 builtin = true，权限不可被改。
 */
@TableName(value = "role", autoResultMap = true)
public class Role {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 权限项清单，JSON 列，如 ["order.accept","prep.view"] */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> permissions;

    /** 内置角色：不可删除、不可改权限 */
    private boolean builtin;

    private LocalDateTime createdAt;

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

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

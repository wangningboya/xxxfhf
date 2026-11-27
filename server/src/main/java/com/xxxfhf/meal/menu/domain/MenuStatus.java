package com.xxxfhf.meal.menu.domain;

/** 菜单状态。与建表脚本 `daily_menu`.`status` 的注释一致。 */
public final class MenuStatus {

    private MenuStatus() {
    }

    /** 草稿：只有商家看得到，用户端不返回 */
    public static final String DRAFT = "draft";
    /** 已发布：用户端可见 */
    public static final String PUBLISHED = "published";

    public static boolean isValid(String status) {
        return DRAFT.equals(status) || PUBLISHED.equals(status);
    }
}

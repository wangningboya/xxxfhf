package com.xxxfhf.meal.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注接口所需权限，任一命中即放行（多传为「或」的关系）。
 *
 * 例：@RequirePerm({Perms.COMBO_MANAGE, Perms.DISH_MANAGE})
 * 只对商家账号（user_type = merchant）生效；群成员一律拒绝。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    String[] value();
}

package com.xxxfhf.meal.user.domain;

/**
 * 账号类型。user 表用一列（user_type）区分两类身份，不靠角色。
 *
 * 角色（role_ids）只负责「这个人能做什么」，不负责「这个人是哪类人」。
 */
public final class UserType {

    private UserType() {
    }

    /** 群成员：微信 openid 登录，进入小程序，需审核后方可下单 */
    public static final String CUSTOMER = "customer";

    /** 商家：账号密码登录，进入网页后台 */
    public static final String MERCHANT = "merchant";
}

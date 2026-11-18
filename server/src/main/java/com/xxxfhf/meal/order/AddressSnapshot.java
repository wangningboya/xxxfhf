package com.xxxfhf.meal.order;

/**
 * 地址快照。存在 `order`.`address_snapshot`。
 *
 * 收货人、电话、详细地址一起抄进订单：之后用户改地址、商家删地址，都不影响这一单送哪儿。
 * 自取单没有地址：只填联系人（用户昵称）与电话，电话选填、可能为 null，只用于留痕与备菜对名字。
 */
public record AddressSnapshot(String contactName, String phone, String detail,
                              String regionName, String tag) {
}

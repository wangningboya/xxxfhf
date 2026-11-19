package com.xxxfhf.meal.order;

/**
 * 区域快照。存在 `order`.`region_snapshot`。
 *
 * 下单那一刻抄下来，之后区域改名、停用、甚至被停用后重建，历史订单与备菜统计都不受影响。
 */
public record RegionSnapshot(Long regionId, String regionName) {
}

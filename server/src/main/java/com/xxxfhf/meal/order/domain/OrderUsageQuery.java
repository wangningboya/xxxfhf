package com.xxxfhf.meal.order.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 「某个盒饭有没有被历史订单引用过」——这条判断读的是 {@code order.items}，所以归订单域。
 *
 * <p>放在 order 包里，改 {@code items} 结构（加字段、改名、换 key）的人就在同一个包内，
 * 不会出现「订单包改完了，goods 包里那处直查 JSON 的 SQL 悄悄失效」。
 *
 * <p>实现用 JdbcTemplate 而不是 OrderMapper：只需要一条计数 SQL，没有实体、没有业务逻辑；
 * 而且本类不依赖 goods 包，任何域都可以注入它而不成环。
 *
 * <p>注意 {@code JSON_CONTAINS(JSON_EXTRACT(...))} 走不了索引，是整表扫描。只在删除盒饭前调一次，
 * 300 单/天无感；订单量到几万以后要给 {@code items} 加生成列 + 多值索引，或改成明细表。
 */
@Component
public class OrderUsageQuery {

    private static final Logger log = LoggerFactory.getLogger(OrderUsageQuery.class);

    /**
     * 按 JSON 里的 comboId 精确判断，不能用 LIKE '%id%'：主键是自增 BIGINT，
     * 数字做子串匹配会把 13、23 也算成引用 3 的订单。
     */
    private static final String COUNT_ORDERS_BY_COMBO_SQL = """
            SELECT COUNT(*) FROM `order`
             WHERE JSON_CONTAINS(JSON_EXTRACT(items, '$[*].comboId'), CAST(? AS CHAR))
            """;

    /**
     * 区域内历史订单数。region_id 上有索引，但它是 {@code idx_order_date_region (serve_date, region_id)}
     * 的第二列，单独按 region_id 过滤用不上，仍是整表扫；同样只在删除区域前调一次。
     */
    private static final String COUNT_ORDERS_BY_REGION_SQL = """
            SELECT COUNT(*) FROM `order` WHERE region_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public OrderUsageQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 引用过该盒饭的订单数（含已取消 / 已拒单：它们同样是「卖过」的凭证）。 */
    public long countOrdersUsingCombo(Long comboId) {
        if (comboId == null) {
            return 0L;
        }
        long used = count(COUNT_ORDERS_BY_COMBO_SQL, comboId);
        if (used > 0) {
            log.debug("盒饭被历史订单引用 comboId={} orders={}", comboId, used);
        }
        return used;
    }

    /** 区域内历史订单数（含已取消 / 已拒单：备菜与对账仍按区域归集它们）。 */
    public long countOrdersUsingRegion(Long regionId) {
        if (regionId == null) {
            return 0L;
        }
        long used = count(COUNT_ORDERS_BY_REGION_SQL, regionId);
        if (used > 0) {
            log.debug("区域被历史订单引用 regionId={} orders={}", regionId, used);
        }
        return used;
    }

    private long count(String sql, Object arg) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, arg);
        return count == null ? 0L : count;
    }
}

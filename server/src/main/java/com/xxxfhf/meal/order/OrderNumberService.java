package com.xxxfhf.meal.order;

import com.xxxfhf.meal.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 订单号与取餐码生成。两者都必须「当日唯一」，但实现方式不同：
 *
 * - 订单号 `yyyyMMdd-000001`：对人报单号用，要能看出是哪天的单，所以用当日流水号
 * - 取餐码 4 位数字：中午高峰要大声念出来，越短越好，所以同日复用环里随机取空位
 *
 * 都靠数据库唯一索引兜底（uk_order_no / uk_order_date_pickup），
 * 这里生成的值冲突时由调用方重试，绝不「先查再插」当唯一性保证。
 */
@Component
public class OrderNumberService {

    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 4 位码的空间是 10000 个，单日 300 单，随机取空位的碰撞概率极低 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;

    public OrderNumberService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 生成当日订单号：取当天最大流水号 + 1。
     *
     * 并发下两个请求可能取到同一个号，插入时撞 uk_order_no 由调用方重试 ——
     * 这是刻意的：让数据库做唯一性判定，比在应用层加锁便宜且不会错。
     */
    public String nextOrderNo() {
        String prefix = LocalDate.now().format(DATE_KEY) + "-";
        String max = jdbcTemplate.queryForObject(
                "SELECT MAX(order_no) FROM `order` WHERE order_no LIKE ?", String.class, prefix + "%");
        int seq = 1;
        if (max != null && max.length() > prefix.length()) {
            try {
                seq = Integer.parseInt(max.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                // 历史上有过格式不同的单号，退化为从头计，靠唯一索引重试
                seq = 1;
            }
        }
        if (seq > 999999) {
            throw BizException.conflict("当日订单号已用尽，请联系技术支持");
        }
        return prefix + String.format("%06d", seq);
    }

    /**
     * 生成某供餐日的 4 位取餐码。
     *
     * 唯一性范围是**供餐日**而不是下单时刻：中午核销时只看当天这一批人会不会撞码。
     */
    public String nextPickupCode(LocalDate serveDate) {
        List<String> used = jdbcTemplate.queryForList(
                "SELECT pickup_code FROM `order` WHERE serve_date = ? AND pickup_code IS NOT NULL",
                String.class, serveDate);
        Set<String> taken = new HashSet<>(used);
        for (int i = 0; i < 100; i++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            if (!taken.contains(code)) {
                return code;
            }
        }
        // 10000 个码里连试 100 次都撞上，说明当日订单量已经逼近上限
        throw BizException.conflict("当日取餐码已用尽，请联系技术支持");
    }
}

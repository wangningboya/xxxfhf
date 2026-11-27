package com.xxxfhf.meal.stat.service;

import com.xxxfhf.meal.menu.domain.DateParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 后台工作台聚合（N-16）。
 *
 * 为什么单独做一个接口而不是让前端并发调 8 个列表接口各取 total：
 * 后台登录后第一屏就要看到「今天有几件事等我做」，8 个请求里任何一个慢都会让首屏空着；
 * 这些数字都是索引上的 COUNT，合成一次查询反而更快。
 *
 * 口径说明：
 * - 「待办」六类都**不限供餐日**：待审核用户、待确认地址本来就没有供餐日概念，
 *   待接单、待处理取消、待核对收款、待退款如果按日过滤，昨天的漏单就再也看不见了 ——
 *   钱的事尤其不能只看今天，上个月少核对一笔，账就永远差一笔
 * - 「营收」按供餐日统计且只算 `paid`：`claimed` 是用户单方面说付了，还没核对，不能计入
 * - 同一件事有两个口径：待办里的 `pendingCheck` / `pendingRefund` 不限日（去做事），
 *   概览里的 `pendingPayment` 按供餐日且是两者之和（看今天这批）。两者并存不是重复，
 *   前端也把前者放在待办区、后者放在当日概览区
 */
@Service
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;

    public DashboardService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param serveDateRaw 供餐日，留空按明天（商家每天处理的就是明天那批）
     */
    public DashboardView summary(String serveDateRaw) {
        LocalDate serveDate = DateParam.parse(serveDateRaw, "供餐日期");
        if (serveDate == null) {
            serveDate = LocalDate.now().plusDays(1);
        }

        int pendingAccept = count("SELECT COUNT(*) FROM `order` "
                + "WHERE status = 'pending_accept'");
        // 取消申请只有「已接单 + 已过截单」才会置位（需求 4.9，v3.48 起）。
        // 这里仍带上 pending_accept：v3.48 之前的老申请挂在待接单上，不扫就会漏。
        int pendingCancel = count("SELECT COUNT(*) FROM `order` "
                + "WHERE cancel_requested = 1 AND status IN ('pending_accept', 'accepted')");
        int pendingAudit = count("SELECT COUNT(*) FROM `user` "
                + "WHERE user_type = 'customer' AND audit_status = 'pending'");
        int pendingAddress = count("SELECT COUNT(*) FROM address WHERE status = 'pending'");

        int dayOrderCount = count("SELECT COUNT(*) FROM `order` WHERE serve_date = ?", serveDate);
        // 金额用 long：SUM(total_amount) 在 MySQL 里返回 DECIMAL / BIGINT，
        // 用 Integer 接会在「店铺做大」那天抛类型转换异常，而不是给出错数
        long dayAmount = sum("SELECT COALESCE(SUM(total_amount), 0) FROM `order` "
                + "WHERE serve_date = ? AND pay_status = 'paid'", serveDate);
        int pendingPayment = count("SELECT COUNT(*) FROM `order` "
                + "WHERE serve_date = ? AND pay_status IN ('claimed', 'refunding')", serveDate);
        int publishedCombos = count("SELECT COUNT(*) FROM daily_menu "
                + "WHERE serve_date = ? AND status = 'published'", serveDate);

        // 资金类待办（v1.53）：不限供餐日，与其余四个待办同口径
        int pendingCheck = count("SELECT COUNT(*) FROM `order` WHERE pay_status = 'claimed'");
        int pendingRefund = count("SELECT COUNT(*) FROM `order` WHERE pay_status = 'refunding'");
        // 已出餐/已完成但钱没到（v3.50 / F-F07）：不限供餐日，欠得越久越该催
        int pendingCollect = count("SELECT COUNT(*) FROM `order` "
                + "WHERE status IN ('ready', 'completed') AND pay_status IN ('unpaid', 'claimed')");

        return new DashboardView(serveDate, pendingAccept, pendingCancel, pendingAudit,
                pendingAddress, pendingCheck, pendingRefund, pendingCollect,
                dayOrderCount, dayAmount, pendingPayment, publishedCombos);
    }

    private int count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value.intValue();
    }

    private long sum(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /**
     * @param pendingAccept   待接单（不限日，含漏单）
     * @param pendingCancel   待处理取消申请（不限日，含漏单）
     * @param pendingAudit    待审核用户
     * @param pendingAddress  待确认收货地址
     * @param pendingCheck    待核对收款（`claimed`，不限日）
     * @param pendingRefund   待退款（`refunding`，不限日）
     * @param pendingCollect  待收款（已出餐/已完成 + 未收款/声称已付款，不限日；v3.50）
     * @param dayOrderCount   该供餐日订单总数
     * @param dayAmount       该供餐日已确认收款金额（分）
     * @param pendingPayment  该供餐日待核对 / 待退款的单数
     * @param publishedCombos 该供餐日已发布盒饭数
     */
    public record DashboardView(LocalDate serveDate,
                                int pendingAccept, int pendingCancel, int pendingAudit,
                                int pendingAddress, int pendingCheck, int pendingRefund,
                                int pendingCollect,
                                int dayOrderCount, long dayAmount,
                                int pendingPayment, int publishedCombos) {
    }
}

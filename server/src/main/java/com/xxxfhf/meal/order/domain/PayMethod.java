package com.xxxfhf.meal.order.domain;

import java.util.List;

/**
 * 收款方式（需求 4.10 / 4.20 / D-25）。
 *
 * <p><b>选项已改由字典 {@code pay_method} 管理</b>：商家可以在「数据字典」里新增
 * 「银行转账」这类方式，代码里不能再写死三个值做校验 —— 校验统一走
 * {@code DictService.requireValue}，显示名统一走 {@code DictService.label}。
 *
 * <p>这里保留的只有两件事：
 * <ul>
 *   <li>初始三项的存值常量与中文名，用于建库初始数据与字典缺项时的兜底显示
 *       （字典被误删也不会把「微信」显示成 {@code wechat}）；</li>
 *   <li>{@link #label} 作为 {@code DictService.label} 的兜底实现：
 *       查不到字典项时**原样返回存值**，这样商家自建项被删后，历史订单上还能看到
 *       当时用的到底是什么（需求里明确要求的「字典没了就显示原 code」）。</li>
 * </ul>
 *
 * <p>同一个值会落两处：{@code order.pay_method} 存当前值（供列表与对账直读），
 * {@code order_status_log.pay_method} 存申报 / 确认当时的快照（供回溯差异）。
 * 两处都只存 {@code item_value}，中文名不落库 —— 改显示名时历史记录跟着变。
 */
public final class PayMethod {

    /** 微信 */
    public static final String WECHAT = "wechat";
    /** 支付宝 */
    public static final String ALIPAY = "alipay";
    /** 现金 */
    public static final String CASH = "cash";

    /** 初始三项。字典里被删/停用时，至少这三项还能显示中文：它们是内置字典项，删不掉只能停用。 */
    private static final List<Option> BUILTIN = List.of(
            new Option(WECHAT, "微信"),
            new Option(ALIPAY, "支付宝"),
            new Option(CASH, "现金")
    );

    private PayMethod() {
    }

    /**
     * 兜底显示名：内置三项给中文，其余原样返回存值。
     *
     * <p>只作 {@code DictService.label(..., PayMethod.label(code))} 的 fallback 用，
     * 页面上的显示名一律以字典为准（商家可能把「微信」改成「微信转账」）。
     */
    public static String label(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (Option option : BUILTIN) {
            if (option.code().equals(code)) {
                return option.label();
            }
        }
        return code;
    }

    /**
     * @param code  存值
     * @param label 中文名
     */
    public record Option(String code, String label) {
    }
}

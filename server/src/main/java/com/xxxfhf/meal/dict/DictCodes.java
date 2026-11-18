package com.xxxfhf.meal.dict;

/**
 * 字典类型编码。代码里一律用这里的常量取值，不写字面量。
 *
 * 与 `deploy/mysql/init/02-data.sql` 的 dict_type 初始数据一一对应。
 */
public final class DictCodes {

    private DictCodes() {
    }

    public static final String ORDER_STATUS = "order_status";
    public static final String PAY_STATUS = "pay_status";
    public static final String ADDRESS_STATUS = "address_status";
    public static final String AUDIT_STATUS = "audit_status";
    public static final String INVITE_STATUS = "invite_status";
    public static final String DELIVERY_TYPE = "delivery_type";
    public static final String NOTIFY_TYPE = "notify_type";

    /**
     * 收款方式。与其他内置类型不同，**此项允许商家自行增删字典项**（见
     * {@code DictService.EXTENSIBLE_TYPES}）：收款渠道不是状态机的一环，
     * 多一个「银行转账」不会让代码走到处理不了的分支。
     */
    public static final String PAY_METHOD = "pay_method";
}

package com.xxxfhf.meal.order.domain;

import java.util.List;

/**
 * 订单状态流转的动作码清单（需求 4.20）。
 *
 * <p>三条线用动作码前缀区分：{@code order.} 订单状态线、{@code pay.} 收款状态线、
 * {@code event.} 不改状态的事件。前缀不是命名习惯，而是 {@code log_type} 的推导依据 ——
 * 埋点时不必再传一次类型，从库里读出来也不会出现「动作是 pay.confirm、类型写着 order」这种对不上的行。
 *
 * <p><b>这张清单是中文名的唯一来源</b>：服务端随行下发 {@code actionLabel}，前端不维护映射表。
 * 新增埋点必须在这里加一项，否则时间轴上显示的是原始码 —— 漏了不报错，只是安静地难看。
 */
public final class OrderLogActions {

    /* ---------------- 流转类型 ---------------- */

    /** 订单状态线 */
    public static final String TYPE_ORDER = "order";
    /** 收款状态线 */
    public static final String TYPE_PAY = "pay";
    /** 不改状态的事件 */
    public static final String TYPE_EVENT = "event";

    /* ---------------- 订单状态线 ---------------- */

    public static final String ORDER_CREATE = "order.create";
    public static final String ORDER_ACCEPT = "order.accept";
    public static final String ORDER_REJECT = "order.reject";
    public static final String ORDER_READY = "order.ready";
    public static final String ORDER_COMPLETE = "order.complete";
    public static final String ORDER_PICKUP = "order.pickup";
    public static final String ORDER_CANCEL = "order.cancel";
    /**
     * 供餐日已过、仍没人接单 → 系统自动关闭（v3.50）。
     *
     * <p>与 {@code order.cancel} 分开：目标状态同为「已取消」，但「谁关的、为什么关」
     * 是两件事。合成一个码之后，事后想知道「有多少单是被系统关掉的」只能靠猜。
     */
    public static final String ORDER_EXPIRE = "order.expire";

    /* ---------------- 收款状态线 ---------------- */

    public static final String PAY_CLAIM = "pay.claim";
    public static final String PAY_CONFIRM = "pay.confirm";
    public static final String PAY_REVERT = "pay.revert";
    public static final String PAY_REFUNDING = "pay.refunding";
    public static final String PAY_REFUND = "pay.refund";

    /* ---------------- 业务事件 ---------------- */

    public static final String EVENT_CANCEL_APPLY = "event.cancel_apply";
    public static final String EVENT_CANCEL_DENY = "event.cancel_deny";
    public static final String EVENT_STOCK_RELEASE = "event.stock_release";
    /** 订阅消息发送成功 */
    public static final String EVENT_NOTIFY = "event.notify";
    /** 订阅消息发送失败 */
    public static final String EVENT_NOTIFY_FAIL = "event.notify_fail";
    /**
     * 商家调取顾客完整号码用于拨号。不改状态，但必须留痕：
     * 号码平时是掩码的，这一次「看了全号」得有据可查。
     */
    public static final String EVENT_CONTACT_PHONE = "event.contact_phone";

    private static final List<Action> ALL = List.of(
            new Action(ORDER_CREATE, "下单"),
            new Action(ORDER_ACCEPT, "接单"),
            new Action(ORDER_REJECT, "拒单"),
            new Action(ORDER_READY, "出餐"),
            new Action(ORDER_COMPLETE, "完成订单"),
            new Action(ORDER_PICKUP, "取餐核销"),
            new Action(ORDER_CANCEL, "取消订单"),
            new Action(ORDER_EXPIRE, "超时未接单关闭"),
            new Action(PAY_CLAIM, "用户声称已付款"),
            new Action(PAY_CONFIRM, "确认收款"),
            new Action(PAY_REVERT, "收款回退"),
            new Action(PAY_REFUNDING, "转入待退款"),
            new Action(PAY_REFUND, "标记退款"),
            new Action(EVENT_CANCEL_APPLY, "申请取消"),
            new Action(EVENT_CANCEL_DENY, "驳回取消申请"),
            new Action(EVENT_STOCK_RELEASE, "超时释放份数"),
            new Action(EVENT_NOTIFY, "通知已发送"),
            new Action(EVENT_NOTIFY_FAIL, "通知未送达"),
            new Action(EVENT_CONTACT_PHONE, "拨打顾客电话")
    );

    private OrderLogActions() {
    }

    public static List<Action> all() {
        return ALL;
    }

    /** 动作码的中文名；未知码原样返回，避免时间轴出现空白 */
    public static String label(String code) {
        for (Action action : ALL) {
            if (action.code().equals(code)) {
                return action.label();
            }
        }
        return code == null ? "" : code;
    }

    /**
     * 由动作码推导流转类型。三段前缀之外的值一律按事件处理 ——
     * 宁可少一条状态线，也不要抛出异常把整条时间轴带崩。
     */
    public static String logTypeOf(String action) {
        String code = action == null ? "" : action;
        if (code.startsWith(TYPE_ORDER + ".")) {
            return TYPE_ORDER;
        }
        if (code.startsWith(TYPE_PAY + ".")) {
            return TYPE_PAY;
        }
        return TYPE_EVENT;
    }

    /**
     * @param code  动作码
     * @param label 中文名
     */
    public record Action(String code, String label) {
    }
}

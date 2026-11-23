package com.xxxfhf.meal.finance;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.order.Order;
import com.xxxfhf.meal.order.OrderItem;
import com.xxxfhf.meal.order.OrderMapper;
import com.xxxfhf.meal.order.OrderStatus;
import com.xxxfhf.meal.order.PayStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账视图（F-4.16）。
 *
 * 线下转账与现金都没有支付流水，**系统里这几个数就是唯一的账**，所以口径必须写死在这里：
 *
 * 应收 = 未取消且未拒单的订单金额合计
 * 已确认收款 = 收款状态 paid
 * 未收款 = 收款状态 unpaid（用户没点付款）
 * 用户声称已付款 = 收款状态 claimed（用户说付了，商家还没核）
 * 待退款 = 收款状态 refunding（已收的钱挂着要退）
 * 已退款 = 收款状态 refunded，金额取**实际退款额**（refund_amount，为空回退订单合计）
 *
 * 为什么要单独列「待退款」：不列出来，应收 ≠ 已收 + 未收 + 已退，
 * 商家月底对账会永远差一笔钱且找不到去向。
 */
@Service
public class FinanceService {

    private final OrderMapper orderMapper;
    private final ServeDateClock clock;

    public FinanceService(OrderMapper orderMapper, ServeDateClock clock) {
        this.orderMapper = orderMapper;
        this.clock = clock;
    }

    public FinanceSummary summary(LocalDate serveDate) {
        LocalDate date = serveDate == null ? clock.tomorrow() : serveDate;
        List<Order> orders = ordersOf(date);

        Accumulator receivable = new Accumulator();
        Accumulator paid = new Accumulator();
        Accumulator unpaid = new Accumulator();
        Accumulator claimed = new Accumulator();
        Accumulator refunding = new Accumulator();
        Accumulator refunded = new Accumulator();

        for (Order order : orders) {
            int amount = order.getTotalAmount() == null ? 0 : order.getTotalAmount();
            boolean valid = !OrderStatus.CANCELLED.equals(order.getStatus())
                    && !OrderStatus.REJECTED.equals(order.getStatus());
            if (valid) {
                receivable.add(amount);
            }
            switch (order.getPayStatus() == null ? "" : order.getPayStatus()) {
                // 已取消 / 已拒单的「未收款」不计入未收：那笔钱本来就不该收，算进去等于虚增欠款
                case PayStatus.UNPAID -> {
                    if (valid) {
                        unpaid.add(amount);
                    }
                }
                case PayStatus.CLAIMED -> {
                    if (valid) {
                        claimed.add(amount);
                    }
                }
                case PayStatus.PAID -> {
                    if (valid) {
                        paid.add(amount);
                    }
                }
                // 退款相关不分有效单：钱确实动过，取消单也要留痕
                case PayStatus.REFUNDING -> refunding.add(amount);
                // 已退款按**实际退的钱**累加，不是订单合计：线下退款天然会有部分退
                // （少送一份、扣掉已发生的配送费），按合计算出来的「已退款」是虚的。
                // refund_amount 为空的是 v3.49 之前的历史单，回退订单合计
                case PayStatus.REFUNDED -> refunded.add(refundedAmount(order));
                default -> {
                }
            }
        }

        return new FinanceSummary(date, receivable.toBucket(), paid.toBucket(), unpaid.toBucket(),
                claimed.toBucket(), refunding.toBucket(), refunded.toBucket(), orders.size());
    }

    /**
     * 下钻：某个数字对应的订单清单。
     *
     * @param bucket receivable / paid / unpaid / claimed / refunding / refunded
     */
    public List<FinanceRow> detail(LocalDate serveDate, String bucket) {
        LocalDate date = serveDate == null ? clock.tomorrow() : serveDate;
        String key = bucket == null ? "" : bucket.trim();
        List<FinanceRow> rows = new ArrayList<>();
        for (Order order : ordersOf(date)) {
            if (!matches(order, key)) {
                continue;
            }
            rows.add(new FinanceRow(order.getOrderNo(),
                    order.getAddressSnapshot() == null ? null : order.getAddressSnapshot().contactName(),
                    order.getDeliveryType(),
                    OrderStatus.label(order.getStatus()),
                    PayStatus.label(order.getPayStatus()),
                    order.getTotalAmount() == null ? 0 : order.getTotalAmount(),
                    // 非已退款的单这里是 0，前端只在已退款行上展示
                    PayStatus.REFUNDED.equals(order.getPayStatus()) ? refundedAmount(order) : 0,
                    order.getRefundRemark(),
                    itemsSummary(order)));
        }
        return rows;
    }

    /** 导出一个文件两个 Sheet：对账汇总、订单明细。 */
    public byte[] export(LocalDate serveDate) {
        LocalDate date = serveDate == null ? clock.tomorrow() : serveDate;
        FinanceSummary summary = summary(date);

        List<SummaryExcelRow> summaryRows = List.of(
                new SummaryExcelRow("应收（全部有效订单）", summary.receivable().amount(), summary.receivable().count()),
                new SummaryExcelRow("已确认收款", summary.paid().amount(), summary.paid().count()),
                new SummaryExcelRow("未收款（用户未点付款）", summary.unpaid().amount(), summary.unpaid().count()),
                new SummaryExcelRow("用户声称已付款（待核对）", summary.claimed().amount(), summary.claimed().count()),
                new SummaryExcelRow("待退款", summary.refunding().amount(), summary.refunding().count()),
                new SummaryExcelRow("已退款", summary.refunded().amount(), summary.refunded().count()));

        List<DetailExcelRow> detailRows = new ArrayList<>();
        for (Order order : ordersOf(date)) {
            detailRows.add(new DetailExcelRow(order.getOrderNo(),
                    order.getAddressSnapshot() == null ? null : order.getAddressSnapshot().contactName(),
                    "pickup".equals(order.getDeliveryType()) ? "自取" : "配送",
                    OrderStatus.label(order.getStatus()),
                    PayStatus.label(order.getPayStatus()),
                    yuan(order.getTotalAmount()),
                    PayStatus.REFUNDED.equals(order.getPayStatus())
                            ? yuan(refundedAmount(order)) : "",
                    order.getRefundRemark() == null ? "" : order.getRefundRemark(),
                    itemsSummary(order)));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            writer.write(summaryRows, EasyExcel.writerSheet(0, "对账汇总")
                    .head(SummaryExcelRow.class).build());
            writer.write(detailRows, EasyExcel.writerSheet(1, "订单明细")
                    .head(DetailExcelRow.class).build());
        }
        return out.toByteArray();
    }

    /* ==================== 内部 ==================== */

    private List<Order> ordersOf(LocalDate serveDate) {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getServeDate, serveDate)
                .orderByAsc(Order::getOrderNo));
    }

    private static boolean matches(Order order, String bucket) {
        boolean valid = !OrderStatus.CANCELLED.equals(order.getStatus())
                && !OrderStatus.REJECTED.equals(order.getStatus());
        return switch (bucket) {
            case "receivable" -> valid;
            case "paid" -> valid && PayStatus.PAID.equals(order.getPayStatus());
            case "unpaid" -> valid && PayStatus.UNPAID.equals(order.getPayStatus());
            case "claimed" -> valid && PayStatus.CLAIMED.equals(order.getPayStatus());
            case "refunding" -> PayStatus.REFUNDING.equals(order.getPayStatus());
            case "refunded" -> PayStatus.REFUNDED.equals(order.getPayStatus());
            default -> throw com.xxxfhf.meal.common.BizException.param(
                    "bucket 只能是 receivable / paid / unpaid / claimed / refunding / refunded");
        };
    }

    /** 实际退款额（分）：优先 refund_amount，为空回退订单合计（兼容 v3.49 之前的历史单） */
    private static int refundedAmount(Order order) {
        if (order.getRefundAmount() != null) {
            return order.getRefundAmount();
        }
        return order.getTotalAmount() == null ? 0 : order.getTotalAmount();
    }

    private static String itemsSummary(Order order) {
        if (order.getItems() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (OrderItem item : order.getItems()) {
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(item.name()).append(" ×").append(item.qtyOrZero());
        }
        return sb.toString();
    }

    /** 分转元，仅用于导出展示。计算一律在分上做。 */
    private static String yuan(Integer cent) {
        int value = cent == null ? 0 : cent;
        return String.format("%d.%02d", value / 100, value % 100);
    }

    private static final class Accumulator {
        private int amount;
        private int count;

        private void add(int value) {
            amount += value;
            count++;
        }

        private Bucket toBucket() {
            return new Bucket(amount, count);
        }
    }

    /* ==================== 视图 ==================== */

    public record Bucket(int amount, int count) {
    }

    public record FinanceSummary(LocalDate serveDate, Bucket receivable, Bucket paid, Bucket unpaid,
                                 Bucket claimed, Bucket refunding, Bucket refunded, int orderCount) {
    }

    public record FinanceRow(String orderNo, String contactName, String deliveryType,
                             String statusLabel, String payStatusLabel, int totalAmount,
                             /** 实际退款额（分）：只有「已退款」的行有值，其余为 0 */
                             int refundAmount, String refundRemark,
                             String items) {
    }

    /* ==================== Excel 行 ==================== */

    @ColumnWidth(28)
    public static class SummaryExcelRow {

        @ExcelProperty("项目")
        private String name;
        @ExcelProperty("金额（元）")
        private String amount;
        @ExcelProperty("订单数")
        private int count;

        public SummaryExcelRow() {
        }

        public SummaryExcelRow(String name, int amountCent, int count) {
            this.name = name;
            this.amount = yuan(amountCent);
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public String getAmount() {
            return amount;
        }

        public int getCount() {
            return count;
        }
    }

    @ColumnWidth(18)
    public static class DetailExcelRow {

        @ExcelProperty("订单号")
        private String orderNo;
        @ExcelProperty("收货人")
        private String contactName;
        @ExcelProperty("取餐方式")
        private String deliveryType;
        @ExcelProperty("订单状态")
        private String statusLabel;
        @ExcelProperty("收款状态")
        private String payStatusLabel;
        @ExcelProperty("金额（元）")
        private String amount;
        @ExcelProperty("退款金额（元）")
        private String refundAmount;
        @ExcelProperty("退款备注")
        @ColumnWidth(28)
        private String refundRemark;
        @ExcelProperty("盒饭明细")
        @ColumnWidth(36)
        private String items;

        public DetailExcelRow() {
        }

        public DetailExcelRow(String orderNo, String contactName, String deliveryType,
                              String statusLabel, String payStatusLabel, String amount,
                              String refundAmount, String refundRemark, String items) {
            this.orderNo = orderNo;
            this.contactName = contactName;
            this.deliveryType = deliveryType;
            this.statusLabel = statusLabel;
            this.payStatusLabel = payStatusLabel;
            this.amount = amount;
            this.refundAmount = refundAmount;
            this.refundRemark = refundRemark;
            this.items = items;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public String getRefundAmount() {
            return refundAmount;
        }

        public String getRefundRemark() {
            return refundRemark;
        }

        public String getContactName() {
            return contactName;
        }

        public String getDeliveryType() {
            return deliveryType;
        }

        public String getStatusLabel() {
            return statusLabel;
        }

        public String getPayStatusLabel() {
            return payStatusLabel;
        }

        public String getAmount() {
            return amount;
        }

        public String getItems() {
            return items;
        }
    }
}

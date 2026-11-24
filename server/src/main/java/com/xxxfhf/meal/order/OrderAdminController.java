package com.xxxfhf.meal.order;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端订单处理。小程序商家页与网页后台共用。
 *
 * 权限分工：看单用 order.view，动单用 order.accept，钱的事用 order.payment。
 */
@RestController
@RequestMapping("/admin/order")
public class OrderAdminController {

    /** xlsx 的媒体类型。导出接口只有这一个返回形式 */
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final OrderAdminService orderAdminService;
    private final OrderService orderService;
    private final OrderStatusLogService statusLogService;

    public OrderAdminController(OrderAdminService orderAdminService, OrderService orderService,
                                OrderStatusLogService statusLogService) {
        this.orderAdminService = orderAdminService;
        this.orderService = orderService;
        this.statusLogService = statusLogService;
    }

    /**
     * 列表。份数已释放的单置顶、待接单优先。
     *
     * @param cancelRequested 传 true 只返回「用户已提交取消申请、等待处理」的单。
     *                        商家漏处理是这条链路最容易出的事，所以要能一键筛出来
     * @param payStatus       收款状态（v1.53）：工作台「待核对收款」「待退款」两个待办点进来时按它筛
     */
    @GetMapping("/list")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<PageResult<OrderAdminService.AdminOrderRow>> list(
            @RequestParam(required = false) String serveDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean cancelRequested,
            @RequestParam(required = false) String payStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(PageResult.of(orderAdminService.list(
                serveDate, status, keyword, cancelRequested, payStatus, page, pageSize)));
    }

    /**
     * 催收清单（v3.50 / 需求 4.21 / `F-F07`）：已出餐或已完成、但钱还没到的单。
     *
     * <p>单独一个接口而不是给 {@code /list} 再加参数：它的排序是「供餐日升序，欠得最久的在前」，
     * 与订单列表的「待接单优先」正好相反，混在一个接口里迟早会被改回去。
     *
     * <p>权限同订单查看与收款：这份清单里每一行都要能直接点「确认收款」。
     */
    @GetMapping("/collect")
    @RequirePerm({Perms.ORDER_PAYMENT, Perms.ORDER_VIEW})
    public ApiResponse<PageResult<OrderAdminService.AdminOrderRow>> collect(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(PageResult.of(orderAdminService.collect(page, pageSize)));
    }

    /**
     * 导出催收清单（xlsx）。
     *
     * <p>用 {@code prep.export} 而不是 {@code order.view}：这张表带顾客与电话，
     * 与备菜表同属「能把明细拿走」的一类，权限口径保持一致。
     */
    @GetMapping("/collect/export")
    @RequirePerm(Perms.PREP_EXPORT)
    public org.springframework.http.ResponseEntity<byte[]> exportCollect() {
        byte[] body = orderAdminService.exportCollect();
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename("待收款_" + java.time.LocalDate.now() + ".xlsx",
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(XLSX)
                .body(body);
    }

    @GetMapping("/detail")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<OrderAdminService.AdminOrderRow> detail(@RequestParam Long orderId) {
        return ApiResponse.ok(orderAdminService.detail(orderId));
    }

    /**
     * 订单状态流转记录（时间轴），按时间正序，中文名与配色随行下发。
     *
     * <p><b>不并入 /detail</b>：详情接口被小程序商家端共用，塞一个只有网页后台用的数组会让它无谓变重；
     * 记录条数随订单生命周期增长，将来加筛选或分页也不必动详情协议。
     */
    @GetMapping("/status-log")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<List<OrderStatusLogService.Row>> statusLog(@RequestParam Long orderId) {
        return ApiResponse.ok(statusLogService.list(orderId));
    }

    /**
     * 联系顾客：取一次完整号码用于拨号。
     *
     * <p>号码平时是掩码的，这个接口每调一次都会写进订单时间轴并受频控约束：
     * 同一笔订单 24 小时最多 5 次、同一操作人每天最多 100 次、订单结束后 7 天关闭。
     * 前端拿到就拨号，不放进页面数据、不落缓存 —— 它不是「查号码」的通用入口。
     */
    @GetMapping("/{orderId}/contact-phone")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<String> contactPhone(@PathVariable Long orderId) {
        return ApiResponse.ok(orderAdminService.contactPhone(UserContext.userId(), orderId));
    }

    /** 接单，支持批量。取消申请中的单会被跳过并给出原因。 */
    @PostMapping("/accept")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderAdminService.AcceptResult> accept(@Valid @RequestBody AcceptRequest request) {
        return ApiResponse.ok(orderAdminService.accept(
                UserContext.userId(), request.orderIds()));
    }

    /** 拒单：原因必填、释放份数、已收钱转待退款。 */
    @PostMapping("/reject")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderService.OrderView> reject(@Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(orderAdminService.reject(
                UserContext.userId(), request.orderId(), request.reason()));
    }

    /**
     * 批量拒单（v1.53 / F-J11）：整批共用一条原因。
     *
     * <p>逐单走 {@code reject} 同一条路径，状态已变的单收进 {@code skipped}，
     * 整批全部跳过也不报错 —— 由前端把 {@code skipped} 原样展示，商家自己决定要不要重试。
     */
    @PostMapping("/reject-batch")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderAdminService.RejectResult> rejectBatch(
            @Valid @RequestBody RejectBatchRequest request) {
        return ApiResponse.ok(orderAdminService.rejectBatch(
                UserContext.userId(), request.orderIds(), request.reason()));
    }

    /** 推进状态：accepted → ready → completed。自取与配送共用 ready，只是文案不同。 */
    @PostMapping("/advance")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderService.OrderView> advance(@Valid @RequestBody AdvanceRequest request) {
        return ApiResponse.ok(orderAdminService.advance(
                UserContext.userId(), request.orderId(), request.status()));
    }

    /** 输 4 位取餐码核销。serveDate 留空按今天。 */
    @PostMapping("/pickup")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderService.OrderView> pickup(@Valid @RequestBody PickupRequest request) {
        return ApiResponse.ok(orderAdminService.pickupByCode(
                UserContext.userId(), request.serveDate(), request.code()));
    }

    /** 收款推进：confirm 确认收款 / revert 回退为未收款。 */
    @PostMapping("/payment")
    @RequirePerm(Perms.ORDER_PAYMENT)
    public ApiResponse<OrderService.OrderView> payment(@Valid @RequestBody PaymentRequest request) {
        Long operatorId = UserContext.userId();
        return switch (request.action()) {
            case "confirm" -> ApiResponse.ok(orderAdminService.confirmPayment(
                    operatorId, request.orderId(), request.payMethod()));
            case "revert" -> ApiResponse.ok(orderAdminService.revertPayment(
                    operatorId, request.orderId(), request.reason()));
            default -> throw com.xxxfhf.meal.common.BizException.param("action 只能是 confirm 或 revert");
        };
    }

    /**
     * 标记退款完成（待退款 → 已退款），记录操作人与时间。
     *
     * <p>v1.53 起必须带 {@code refundAmount}（前端弹窗默认填全额，商家可改成实际退的）。
     * 不带时按订单合计处理，兼容还没更新的旧版小程序。
     */
    @PostMapping("/refund")
    @RequirePerm(Perms.ORDER_PAYMENT)
    public ApiResponse<OrderService.OrderView> refund(@Valid @RequestBody RefundRequest request) {
        return ApiResponse.ok(orderAdminService.refund(UserContext.userId(), request.orderId(),
                request.refundAmount(), request.refundRemark()));
    }

    /** 审批用户的取消申请。 */
    @PostMapping("/cancel-request")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderService.OrderView> cancelRequest(
            @Valid @RequestBody CancelHandleRequest request) {
        return ApiResponse.ok(orderAdminService.handleCancelRequest(
                UserContext.userId(), request.orderId(),
                Boolean.TRUE.equals(request.approve()), request.remark()));
    }

    /**
     * 代客下单（F-H06）：商家替用户下单。
     *
     * 与自助下单的区别是**不看截单时间** —— 顾客临时微信说要一份往往已经过了截单点；
     * 停业、份数上限、菜单发布状态照旧校验，目标用户必须已通过审核。
     *
     * {@code force=true} 时地址不可用也放行（N-44）：商家二次确认后仍要下这一单，
     * 时间轴会记一条「商家强制下单：<原因>」。
     */
    @PostMapping("/proxy")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderService.OrderView> proxy(@Valid @RequestBody ProxyRequest request) {
        return ApiResponse.ok(orderService.createForUser(request.userId(),
                new OrderService.CreateCommand(request.serveDate(), request.deliveryType(),
                        request.addressId(), request.pickupSlot(), request.remark(),
                        request.items(), null),
                Boolean.TRUE.equals(request.force())));
    }

    public record AcceptRequest(
            @NotEmpty(message = "请选择要接单的订单") List<Long> orderIds) {
    }

    /**
     * @param userId 替谁下单。必须是已通过审核的群成员
     * @param force  地址不可用时是否强制下单（默认 false 返回 409）。只跳过地址可用性，
     *               「地址不存在」「不是该用户的地址」仍然拒
     */
    public record ProxyRequest(
            @NotNull(message = "请选择下单用户") Long userId,
            String serveDate,
            String deliveryType,
            Long addressId,
            String pickupSlot,
            String remark,
            @NotEmpty(message = "请选择盒饭") List<OrderService.ItemInput> items,
            Boolean force) {
    }

    public record RejectRequest(
            @NotNull(message = "缺少订单 ID") Long orderId,
            @NotBlank(message = "拒单必须填写原因") String reason) {
    }

    /** @param reason 整批共用的一条原因，必填 */
    public record RejectBatchRequest(
            @NotEmpty(message = "请选择要拒单的订单") List<Long> orderIds,
            @NotBlank(message = "拒单必须填写原因") String reason) {
    }

    /**
     * @param refundAmount 实际退款金额（分）。为空按订单合计；须 &gt;0 且 ≤ 合计
     * @param refundRemark 退款备注（选填，≤200）
     */
    public record RefundRequest(
            @NotNull(message = "缺少订单 ID") Long orderId,
            Integer refundAmount,
            String refundRemark) {
    }

    public record AdvanceRequest(
            @NotNull(message = "缺少订单 ID") Long orderId,
            @NotBlank(message = "缺少目标状态") String status) {
    }

    public record PickupRequest(String serveDate, @NotBlank(message = "请输入取餐码") String code) {
    }

    /**
     * 收款推进。
     *
     * <p>{@code payMethod} 只在 {@code action = confirm} 时必填（校验在
     * {@code DictService.requireValue} 里按字典 {@code pay_method} 做，不在这里加
     * {@code @NotBlank} —— {@code revert} 不需要方式，加了会让回退也跟着失败）。
     *
     * <p>v3.50 起 {@code action = revert} 必须带 {@code reason}：原因会原样进用户的
     * 站内消息。校验放在 Service 里（按 action 分支），不在这里加 {@code @NotBlank}。
     */
    public record PaymentRequest(
            @NotNull(message = "缺少订单 ID") Long orderId,
            @NotBlank(message = "缺少操作类型") String action,
            String payMethod,
            String reason) {
    }

    public record IdRequest(@NotNull(message = "缺少订单 ID") Long orderId) {
    }

    public record CancelHandleRequest(
            @NotNull(message = "缺少订单 ID") Long orderId,
            Boolean approve,
            String remark) {
    }
}

package com.xxxfhf.meal.order;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final OrderAdminService orderAdminService;
    private final OrderService orderService;

    public OrderAdminController(OrderAdminService orderAdminService, OrderService orderService) {
        this.orderAdminService = orderAdminService;
        this.orderService = orderService;
    }

    /**
     * 列表。份数已释放的单置顶、待接单优先。
     *
     * @param cancelRequested 传 true 只返回「用户已提交取消申请、等待处理」的单。
     *                        商家漏处理是这条链路最容易出的事，所以要能一键筛出来
     */
    @GetMapping("/list")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<PageResult<OrderAdminService.AdminOrderRow>> list(
            @RequestParam(required = false) String serveDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean cancelRequested,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(PageResult.of(
                orderAdminService.list(serveDate, status, keyword, cancelRequested, page, pageSize)));
    }

    @GetMapping("/detail")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<OrderAdminService.AdminOrderRow> detail(@RequestParam String orderId) {
        return ApiResponse.ok(orderAdminService.detail(orderId));
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

    /** 推进状态：making / ready / completed。 */
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
        String operatorId = UserContext.userId();
        return switch (request.action()) {
            case "confirm" -> ApiResponse.ok(orderAdminService.confirmPayment(operatorId, request.orderId()));
            case "revert" -> ApiResponse.ok(orderAdminService.revertPayment(operatorId, request.orderId()));
            default -> throw com.xxxfhf.meal.common.BizException.param("action 只能是 confirm 或 revert");
        };
    }

    /** 标记退款完成（待退款 → 已退款），记录操作人与时间。 */
    @PostMapping("/refund")
    @RequirePerm(Perms.ORDER_PAYMENT)
    public ApiResponse<OrderService.OrderView> refund(@Valid @RequestBody IdRequest request) {
        return ApiResponse.ok(orderAdminService.refund(UserContext.userId(), request.orderId()));
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
     */
    @PostMapping("/proxy")
    @RequirePerm(Perms.ORDER_ACCEPT)
    public ApiResponse<OrderService.OrderView> proxy(@Valid @RequestBody ProxyRequest request) {
        return ApiResponse.ok(orderService.createForUser(request.userId(),
                new OrderService.CreateCommand(request.serveDate(), request.deliveryType(),
                        request.addressId(), request.pickupSlot(), request.remark(),
                        request.items(), null)));
    }

    public record AcceptRequest(
            @NotEmpty(message = "请选择要接单的订单") List<String> orderIds) {
    }

    /**
     * @param userId 替谁下单。必须是已通过审核的群成员
     */
    public record ProxyRequest(
            @NotBlank(message = "请选择下单用户") String userId,
            String serveDate,
            String deliveryType,
            String addressId,
            String pickupSlot,
            String remark,
            @NotEmpty(message = "请选择盒饭") List<OrderService.ItemInput> items) {
    }

    public record RejectRequest(
            @NotBlank(message = "缺少订单 ID") String orderId,
            @NotBlank(message = "拒单必须填写原因") String reason) {
    }

    public record AdvanceRequest(
            @NotBlank(message = "缺少订单 ID") String orderId,
            @NotBlank(message = "缺少目标状态") String status) {
    }

    public record PickupRequest(String serveDate, @NotBlank(message = "请输入取餐码") String code) {
    }

    public record PaymentRequest(
            @NotBlank(message = "缺少订单 ID") String orderId,
            @NotBlank(message = "缺少操作类型") String action) {
    }

    public record IdRequest(@NotBlank(message = "缺少订单 ID") String orderId) {
    }

    public record CancelHandleRequest(
            @NotBlank(message = "缺少订单 ID") String orderId,
            Boolean approve,
            String remark) {
    }
}

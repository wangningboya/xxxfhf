package com.xxxfhf.meal.order.controller;

import com.xxxfhf.meal.auth.AccessGuard;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import com.xxxfhf.meal.order.domain.CancelRequest;
import com.xxxfhf.meal.order.service.OrderService;
import com.xxxfhf.meal.order.service.OrderStatusLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端订单。
 *
 * 下单 / 声称付款 / 取消走 {@link AccessGuard#requireApproved}；
 * 查询自己的订单只要求已准入 —— 待审核用户看不到下单按钮，但历史订单还得能看。
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;
    private final AccessGuard accessGuard;

    public OrderController(OrderService orderService, AccessGuard accessGuard) {
        this.orderService = orderService;
        this.accessGuard = accessGuard;
    }

    /** 下单。成功即占用份数并生成 4 位取餐码。 */
    @PostMapping("")
    public ApiResponse<OrderService.OrderView> create(
            @RequestBody OrderService.CreateCommand command) {
        return ApiResponse.ok(orderService.create(UserContext.userId(), command));
    }

    /** 我的订单，可按主状态筛选。 */
    @GetMapping("/mine")
    public ApiResponse<PageResult<OrderService.OrderView>> mine(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = UserContext.userId();
        accessGuard.requireAccess(userId);
        return ApiResponse.ok(PageResult.of(orderService.mine(userId, status, page, pageSize)));
    }

    @GetMapping("/detail")
    public ApiResponse<OrderService.OrderView> detail(@RequestParam Long orderId) {
        Long userId = UserContext.userId();
        accessGuard.requireAccess(userId);
        return ApiResponse.ok(orderService.detail(userId, orderId));
    }

    /**
     * 按订单号取详情。**小程序码落地页用**。
     *
     * <p>扫码进来的人手里只有 {@code scene} 里的订单号，没有订单 ID。
     * 只校验归属（非本人 403），与 {@code /detail} 同一口径，没有额外暴露。
     */
    @GetMapping("/by-no")
    public ApiResponse<OrderService.OrderView> byNo(@RequestParam String orderNo) {
        Long userId = UserContext.userId();
        accessGuard.requireAccess(userId);
        return ApiResponse.ok(orderService.viewByOrderNo(userId, orderNo));
    }

    /**
     * 我的订单时间轴。
     *
     * <p>独立于 {@code /detail}：时间轴条数随订单生命周期增长，混进详情会让每次打开都多传
     * 一个数组；商家端详情走的是 {@code /admin/order/status-log}，两边本来也不是同一份数据
     * —— 那边要全部记录（含推送投递成败），这边只要订单流转。
     */
    @GetMapping("/{id}/timeline")
    public ApiResponse<List<OrderStatusLogService.Row>> timeline(@PathVariable Long id) {
        Long userId = UserContext.userId();
        accessGuard.requireAccess(userId);
        return ApiResponse.ok(orderService.timeline(userId, id));
    }

    /** 我已完成付款 → 收款状态置为「用户声称已付款」，等商家核对。收款方式必填。 */
    @PostMapping("/claim-paid")
    public ApiResponse<OrderService.OrderView> claimPaid(@Valid @RequestBody ClaimPaidRequest request) {
        return ApiResponse.ok(orderService.claimPaid(
                UserContext.userId(), request.orderId(), request.payMethod()));
    }

    /** 取消 / 申请取消。截单前直接取消，截单后转为申请等商家审批。 */
    @PostMapping("/cancel")
    public ApiResponse<OrderService.OrderView> cancel(@Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(orderService.cancel(
                UserContext.userId(), request.orderId(), request.reason()));
    }

    public record IdRequest(@NotNull(message = "缺少订单 ID") Long orderId) {
    }

    /**
     * 我已完成付款。{@code payMethod} 不带 {@code @NotBlank} ——
     * 校验交给 {@code DictService.requireValue}（字典 {@code pay_method} 里启用着的项才算合法），
     * 让它同时覆盖「缺值」与「已停用 / 不存在的值」，错误文案也能说清是哪种情况。
     */
    public record ClaimPaidRequest(@NotNull(message = "缺少订单 ID") Long orderId,
                                   String payMethod) {
    }

    public record CancelRequest(@NotNull(message = "缺少订单 ID") Long orderId,
                                String reason) {
    }
}

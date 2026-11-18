package com.xxxfhf.meal.order;

import com.xxxfhf.meal.auth.AccessGuard;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        String userId = UserContext.userId();
        accessGuard.requireAccess(userId);
        return ApiResponse.ok(PageResult.of(orderService.mine(userId, status, page, pageSize)));
    }

    @GetMapping("/detail")
    public ApiResponse<OrderService.OrderView> detail(@RequestParam String orderId) {
        String userId = UserContext.userId();
        accessGuard.requireAccess(userId);
        return ApiResponse.ok(orderService.detail(userId, orderId));
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

    public record IdRequest(@NotBlank(message = "缺少订单 ID") String orderId) {
    }

    /**
     * 我已完成付款。{@code payMethod} 不带 {@code @NotBlank} ——
     * 校验交给 {@code DictService.requireValue}（字典 {@code pay_method} 里启用着的项才算合法），
     * 让它同时覆盖「缺值」与「已停用 / 不存在的值」，错误文案也能说清是哪种情况。
     */
    public record ClaimPaidRequest(@NotBlank(message = "缺少订单 ID") String orderId,
                                   String payMethod) {
    }

    public record CancelRequest(@NotBlank(message = "缺少订单 ID") String orderId,
                                String reason) {
    }
}

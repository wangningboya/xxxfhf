package com.xxxfhf.meal.address;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地址维护（商家端）：主路径。商家录的地址直接可用，用户提交的在这里确认。
 */
@RestController
@RequestMapping("/admin/address")
public class AdminAddressController {

    private final AddressService addressService;

    public AdminAddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    /**
     * 地址队列（跨用户）。默认落「待确认」。
     *
     * userId 从必填改为可选：进页面就该看到待办，而不是先被要求选一个人。
     * 想「只看某个人的地址」时把它当筛选条件传进来，行为与原来的 listByUser 一致。
     *
     * enabled 用于「只看已停用的地址」（N-44）：传 0 只看停用、传 1 只看启用、不传全部。
     * 区域管理页的地址抽屉也走这个接口，只多带一个 regionId。
     */
    @GetMapping("/list")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<PageResult<AddressService.AddressRow>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(
                addressService.page(status, enabled, userId, regionId, keyword, page, pageSize));
    }

    @PostMapping("/save")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<Address> save(@RequestBody AddressService.AddressCommand command) {
        return ApiResponse.ok(addressService.saveByMerchant(command));
    }

    /**
     * 审核用户提交的地址：pending → active 通过 / rejected 驳回（必须给原因）/ 收回为 pending。
     *
     * 驳回而不是删除：删掉之后用户端地址凭空消失，他不知道发生了什么，
     * 只会再提一次同样错的地址。
     */
    @PostMapping("/status")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<Address> updateStatus(@Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(
                addressService.updateStatus(request.addressId(), request.status(), request.reason()));
    }

    /**
     * 地址启用 / 停用（N-44）。与审核状态正交：不动 pending / active / rejected。
     *
     * 不校验区域状态 —— 区域停用时商家仍要能整理地址，否则只能等区域恢复。
     * 停用原因可选填（≤200 字），用户端会看到「已停用：装修」这样的说明。
     */
    @PostMapping("/enabled")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<Address> updateEnabled(@Valid @RequestBody EnabledRequest request) {
        return ApiResponse.ok(
                addressService.updateEnabled(request.addressId(), request.enabled(), request.reason()));
    }

    @PostMapping("/delete")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<Void> delete(@Valid @RequestBody IdRequest request) {
        addressService.delete(request.addressId());
        return ApiResponse.ok();
    }

    public record EnabledRequest(
            @NotNull(message = "缺少地址 ID") Long addressId,
            @NotNull(message = "缺少启用状态") Boolean enabled,
            @Size(max = 200, message = "停用原因最多 200 字") String reason) {
    }

    public record StatusRequest(
            @NotNull(message = "缺少地址 ID") Long addressId,
            @NotBlank(message = "缺少目标状态") String status,
            @Size(max = 200, message = "驳回原因最多 200 字") String reason) {
    }

    public record IdRequest(@NotNull(message = "缺少地址 ID") Long addressId) {
    }
}

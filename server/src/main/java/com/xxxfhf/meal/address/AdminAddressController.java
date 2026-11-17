package com.xxxfhf.meal.address;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/list")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<List<Address>> list(@RequestParam String userId) {
        return ApiResponse.ok(addressService.listByUser(userId));
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

    @PostMapping("/delete")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<Void> delete(@Valid @RequestBody IdRequest request) {
        addressService.delete(request.addressId());
        return ApiResponse.ok();
    }

    public record StatusRequest(
            @NotBlank(message = "缺少地址 ID") String addressId,
            @NotBlank(message = "缺少目标状态") String status,
            @Size(max = 200, message = "驳回原因最多 200 字") String reason) {
    }

    public record IdRequest(@NotBlank(message = "缺少地址 ID") String addressId) {
    }
}

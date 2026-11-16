package com.xxxfhf.meal.address;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    /** 确认用户提交的地址（pending → active），或收回（active → pending）。 */
    @PostMapping("/status")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<Address> updateStatus(@Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(addressService.updateStatus(request.addressId(), request.status()));
    }

    @PostMapping("/delete")
    @RequirePerm(Perms.USER_ADDRESS)
    public ApiResponse<Void> delete(@Valid @RequestBody IdRequest request) {
        addressService.delete(request.addressId());
        return ApiResponse.ok();
    }

    public record StatusRequest(
            @NotBlank(message = "缺少地址 ID") String addressId,
            @NotBlank(message = "缺少目标状态") String status) {
    }

    public record IdRequest(@NotBlank(message = "缺少地址 ID") String addressId) {
    }
}

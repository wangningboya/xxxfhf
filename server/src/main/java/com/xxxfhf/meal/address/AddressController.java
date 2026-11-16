package com.xxxfhf.meal.address;

import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户自己的地址（用户端）。
 *
 * 没有 delete：地址的增删由商家掌握，用户只能新增与修改，
 * 且两者都落 `pending` 等商家确认 —— 避免用户把商家录好的配送地址改乱或删掉。
 */
@RestController
@RequestMapping("/address")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    /** 我的地址。含待确认的，前端按 status 区分展示（待确认的不能选来下单）。 */
    @GetMapping("/mine")
    public ApiResponse<List<Address>> mine() {
        return ApiResponse.ok(addressService.listByUser(UserContext.userId()));
    }

    /** 新增 / 修改我的地址，提交后状态为待商家确认。 */
    @PostMapping("")
    public ApiResponse<Address> save(@RequestBody AddressService.AddressCommand command) {
        return ApiResponse.ok(addressService.saveByUser(UserContext.userId(), command));
    }
}

package com.xxxfhf.meal.address;

import com.xxxfhf.meal.auth.AccessGuard;
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
 *
 * 读写分开守：**看**只要求已准入（待审核用户也能看自己已有的地址），
 * **写**要求已审核通过 —— 否则待审核的人填完地址、商家还抽空确认了，最后下单才被拒（需求 4.17）。
 */
@RestController
@RequestMapping("/address")
public class AddressController {

    private final AddressService addressService;
    private final AccessGuard accessGuard;

    public AddressController(AddressService addressService, AccessGuard accessGuard) {
        this.addressService = addressService;
        this.accessGuard = accessGuard;
    }

    /** 我的地址。含待确认的，前端按 status 区分展示（待确认的不能选来下单）。 */
    @GetMapping("/mine")
    public ApiResponse<List<Address>> mine() {
        return ApiResponse.ok(addressService.listByUser(UserContext.userId()));
    }

    /** 新增 / 修改我的地址，提交后状态为待商家确认。待审核用户不能写。 */
    @PostMapping("")
    public ApiResponse<Address> save(@RequestBody AddressService.AddressCommand command) {
        accessGuard.requireApproved(UserContext.userId());
        return ApiResponse.ok(addressService.saveByUser(UserContext.userId(), command));
    }
}

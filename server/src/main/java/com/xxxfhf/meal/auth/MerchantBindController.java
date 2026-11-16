package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端：把当前登录账号与一个微信号绑定，用于接收订阅消息。
 *
 * 为什么不放在 /auth 下：它只服务商家账号，进小程序后由商家端调用，
 * 和群成员的登录、准入不是一回事。
 */
@RestController
@RequestMapping("/admin")
public class MerchantBindController {

    private final MerchantBindService merchantBindService;

    public MerchantBindController(MerchantBindService merchantBindService) {
        this.merchantBindService = merchantBindService;
    }

    /**
     * 绑定接收通知的微信。
     *
     * 前置：已用商家账号登录（拿 merchant 令牌）。前端在小程序里静默 wx.login 取 code 提交，
     * 服务端换 openid 后写入本账号。用户无感知，不会弹授权框。
     */
    @PostMapping("/bind-openid")
    public ApiResponse<MerchantBindService.BindResult> bindOpenid(
            @Valid @RequestBody BindRequest request) {
        return ApiResponse.ok(merchantBindService.bindOpenid(UserContext.userId(), request.code()));
    }

    public record BindRequest(
            @NotBlank(message = "缺少登录 code")
            @Size(max = 256, message = "登录 code 过长") String code) {
    }
}

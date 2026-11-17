package com.xxxfhf.meal.invite;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 邀请码管理（商家端）。
 *
 * 发码等于「放人进来」，所以用审核权限 user.audit 控制。
 */
@RestController
@RequestMapping("/admin/invite")
public class InviteCodeController {

    private final InviteCodeService inviteCodeService;

    public InviteCodeController(InviteCodeService inviteCodeService) {
        this.inviteCodeService = inviteCodeService;
    }

    /** 生成一次性邀请码。expireHours 不传按 24 小时。 */
    @PostMapping("/create")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<InviteCodeView> create(@Valid @RequestBody CreateRequest request) {
        // expireHours 允许不传（null），此处兜住拆箱，Service 侧统一按 24 小时处理
        int hours = request.expireHours() == null ? 24 : request.expireHours();
        InviteCode code = inviteCodeService.create(hours, UserContext.userId());
        return ApiResponse.ok(InviteCodeView.of(code));
    }

    /** 邀请码列表，可按状态筛。 */
    @GetMapping("/list")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<PageResult<InviteCodeView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var result = inviteCodeService.list(status, page, pageSize);
        return ApiResponse.ok(PageResult.of(result.convert(InviteCodeView::of)));
    }

    /** 作废邀请码。发错群、发错人时用，只能作废未使用的码。 */
    @PostMapping("/revoke")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<InviteCodeView> revoke(@Valid @RequestBody RevokeRequest request) {
        return ApiResponse.ok(InviteCodeView.of(inviteCodeService.revoke(request.code())));
    }

    public record CreateRequest(
            @Min(value = 1, message = "有效期至少 1 小时")
            @Max(value = 720, message = "有效期最长 720 小时（30 天）")
            Integer expireHours) {
    }

    public record RevokeRequest(@NotBlank(message = "缺少邀请码") String code) {
    }

    /**
     * 对外视图。code 明文要给商家，他得复制发给用户；
     * 其余字段只保留状态与时间，不返回内部主键与生成人。
     */
    public record InviteCodeView(String code, String status, LocalDateTime expireAt,
                                 LocalDateTime usedAt, LocalDateTime createdAt) {

        static InviteCodeView of(InviteCode code) {
            return new InviteCodeView(code.getCode(), code.getStatus(),
                    code.getExpireAt(), code.getUsedAt(), code.getCreatedAt());
        }
    }
}

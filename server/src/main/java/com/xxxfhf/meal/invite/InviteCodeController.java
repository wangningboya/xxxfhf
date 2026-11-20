package com.xxxfhf.meal.invite;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.common.PageResult;
import com.xxxfhf.meal.user.User;
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
import java.util.Map;

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
        // 使用者一次批量查回来，避免每行再查一次用户
        Map<Long, User> users = inviteCodeService.usersUsedBy(result.getRecords());
        return ApiResponse.ok(PageResult.of(
                result.convert(code -> InviteCodeView.of(code, users.get(code.getUsedBy())))));
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
     * 使用者只回昵称 + 脱敏手机号，不返回生成人。
     */
    public record InviteCodeView(String code, String status, LocalDateTime expireAt,
                                 LocalDateTime usedAt, LocalDateTime createdAt,
                                 Long usedByUserId, String usedByName, String usedByPhone) {

        static InviteCodeView of(InviteCode code) {
            return of(code, null);
        }

        /**
         * @param usedBy 使用者行；码还没被使用就传 null。
         *                使用者已被删除时也是 null，此时只能靠 usedByUserId 表明「确实有人用过」。
         */
        static InviteCodeView of(InviteCode code, User usedBy) {
            String name = null;
            String phone = null;
            if (code.getUsedBy() != null) {
                if (usedBy == null) {
                    // 人删了但码上还留着 used_by：不能让商家误以为这张码没人用过
                    name = "已删除用户 #" + code.getUsedBy();
                } else {
                    String nickName = usedBy.getNickName();
                    name = nickName == null || nickName.isBlank() ? "未填昵称" : nickName;
                    phone = MaskUtil.phone(usedBy.getPhone());
                }
            }
            return new InviteCodeView(code.getCode(), code.getStatus(), code.getExpireAt(),
                    code.getUsedAt(), code.getCreatedAt(),
                    code.getUsedBy(), name, phone);
        }
    }
}

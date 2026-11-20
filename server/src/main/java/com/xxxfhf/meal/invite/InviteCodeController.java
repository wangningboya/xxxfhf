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
import java.util.List;
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
        // 使用者与生成人各一次批量查，避免每行再查一次用户
        Map<Long, User> used = inviteCodeService.usersUsedBy(result.getRecords());
        Map<Long, User> creators = inviteCodeService.usersCreatedBy(result.getRecords());
        return ApiResponse.ok(PageResult.of(
                result.convert(code -> InviteCodeView.of(code,
                        used.get(code.getUsedBy()), creators.get(code.getCreatedBy())))));
    }

    /** 批量生成 1~20 张一次性邀请码。每张码单独记一条审计，事后能对上是哪次发的。 */
    @PostMapping("/batch")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<List<InviteCodeView>> batch(@Valid @RequestBody BatchCreateRequest request) {
        int count = request.count() == null ? 1 : request.count();
        int hours = request.expireHours() == null ? 24 : request.expireHours();
        List<InviteCode> codes = inviteCodeService.createBatch(count, hours, UserContext.userId());
        return ApiResponse.ok(codes.stream().map(InviteCodeView::of).toList());
    }

    /** 后台红点：多少张码被用了但商家还没点开看过。 */
    @GetMapping("/unread-count")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(inviteCodeService.unreadCount());
    }

    /** 商家打开邀请码管理后清红点。只动 read_at，不影响码的状态。 */
    @PostMapping("/mark-read")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<Integer> markRead() {
        return ApiResponse.ok(inviteCodeService.markRead());
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

    /** 批量生成：数量 1~20，有效期同单张。 */
    public record BatchCreateRequest(
            @Min(value = 1, message = "至少生成 1 个邀请码")
            @Max(value = 20, message = "一次最多生成 20 个邀请码")
            Integer count,
            @Min(value = 1, message = "有效期至少 1 小时")
            @Max(value = 720, message = "有效期最长 720 小时（30 天）")
            Integer expireHours) {
    }

    public record RevokeRequest(@NotBlank(message = "缺少邀请码") String code) {
    }

    /**
     * 对外视图。code 明文要给商家，他得复制发给用户；
     * 使用者/生成人只回昵称 + 脱敏手机号（生成人不回手机号）。
     */
    public record InviteCodeView(String code, String status, LocalDateTime expireAt,
                                 LocalDateTime usedAt, LocalDateTime createdAt,
                                 Long usedByUserId, String usedByName, String usedByPhone,
                                 Long createdByUserId, String createdByName) {

        static InviteCodeView of(InviteCode code) {
            return of(code, null, null);
        }

        static InviteCodeView of(InviteCode code, User usedBy) {
            return of(code, usedBy, null);
        }

        /**
         * @param usedBy    使用者行；码还没被使用就传 null。使用者已被删除时也是 null，
         *                  此时只能靠 usedByUserId 表明「确实有人用过」。
         * @param createdBy 生成人行；系统生成（created_by=0）或查不到时按 created_by 兜底显示。
         */
        static InviteCodeView of(InviteCode code, User usedBy, User createdBy) {
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

            Long createdByUserId = code.getCreatedBy();
            String createdByName = null;
            if (createdByUserId != null) {
                if (createdByUserId == 0L) {
                    // 0 = 系统生成（定时任务/脚本），不是某个人
                    createdByName = "系统";
                } else if (createdBy == null) {
                    createdByName = "已删除用户 #" + createdByUserId;
                } else {
                    createdByName = displayName(createdBy);
                }
            }

            return new InviteCodeView(code.getCode(), code.getStatus(), code.getExpireAt(),
                    code.getUsedAt(), code.getCreatedAt(),
                    code.getUsedBy(), name, phone,
                    createdByUserId, createdByName);
        }

        /** 商家账号可能没填昵称，此时退回登录名；两者都空才用 id 兜底。 */
        private static String displayName(User user) {
            String nickName = user.getNickName();
            if (nickName != null && !nickName.isBlank()) {
                return nickName;
            }
            String username = user.getUsername();
            if (username != null && !username.isBlank()) {
                return username;
            }
            return "用户 #" + user.getId();
        }
    }
}

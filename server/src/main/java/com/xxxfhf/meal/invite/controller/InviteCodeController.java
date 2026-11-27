package com.xxxfhf.meal.invite.controller;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.common.PageResult;
import com.xxxfhf.meal.invite.domain.InviteCode;
import com.xxxfhf.meal.invite.domain.InviteUse;
import com.xxxfhf.meal.invite.service.InviteCodeService;
import com.xxxfhf.meal.user.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 *
 * <p>一次生成**一个**码，码上带可用次数；使用者名单只在网页后台看（小程序只显示已用 X/N）。
 */
@RestController
@RequestMapping("/admin/invite")
public class InviteCodeController {

    private final InviteCodeService inviteCodeService;

    public InviteCodeController(InviteCodeService inviteCodeService) {
        this.inviteCodeService = inviteCodeService;
    }

    /** 生成一个邀请码。uses 必填（可用次数），expireHours 不传按 24 小时。 */
    @PostMapping("/create")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<InviteCodeView> create(@Valid @RequestBody CreateRequest request) {
        // expireHours 允许不传（null），此处兜住拆箱，Service 侧统一按 24 小时处理
        int hours = request.expireHours() == null ? 24 : request.expireHours();
        Long operatorId = UserContext.userId();
        InviteCode code = inviteCodeService.create(request.uses(), hours, operatorId);
        // 回显生成人：不回的话列表里是「已删除用户 #id」，商家会以为账号出问题了
        Map<Long, User> creators = inviteCodeService.usersByIds(List.of(operatorId));
        return ApiResponse.ok(InviteCodeView.of(code, creators.get(operatorId)));
    }

    /** 邀请码列表，可按状态筛。 */
    @GetMapping("/list")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<PageResult<InviteCodeView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var result = inviteCodeService.list(status, page, pageSize);
        // 生成人一次批量查，避免每行再查一次用户
        Map<Long, User> creators = inviteCodeService.usersCreatedBy(result.getRecords());
        return ApiResponse.ok(PageResult.of(
                result.convert(code -> InviteCodeView.of(code, creators.get(code.getCreatedBy())))));
    }

    /**
     * 某个码的使用者名单（谁、什么时候用的）。
     *
     * 只在网页后台「使用者」抽屉里调；不塞进列表接口，否则一页邀请码要连带拉几百行明细。
     */
    @GetMapping("/uses")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<List<InviteUseView>> uses(@RequestParam String code) {
        InviteCode invite = inviteCodeService.findByCode(code);
        if (invite == null) {
            throw BizException.notFound("邀请码不存在");
        }
        List<InviteUse> uses = inviteCodeService.usesOf(invite.getId());
        Map<Long, User> users = inviteCodeService.usersByIds(uses.stream().map(InviteUse::getUserId).toList());
        return ApiResponse.ok(uses.stream()
                .map(use -> InviteUseView.of(use, users.get(use.getUserId())))
                .toList());
    }

    /** 后台红点：多少张码被用过但商家还没点开看过（按码数，不按次数）。 */
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

    /** 作废邀请码。发错群、发错人时用，只能作废还有剩余次数的码。 */
    @PostMapping("/revoke")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<InviteCodeView> revoke(@Valid @RequestBody RevokeRequest request) {
        InviteCode code = inviteCodeService.revoke(request.code());
        if (code == null) {
            // 作废成功后又被人删掉这种极端并发，按不存在处理，别把 null 丢给视图
            throw BizException.notFound("邀请码不存在");
        }
        Long creator = code.getCreatedBy();
        Map<Long, User> creators = creator == null ? Map.of() : inviteCodeService.usersByIds(List.of(creator));
        return ApiResponse.ok(InviteCodeView.of(code, creators.get(creator)));
    }

    /** 生成请求：次数必填且 ≥ 1（不设上限），有效期 1~720 小时。 */
    public record CreateRequest(
            @NotNull(message = "请填写可用次数")
            @Min(value = 1, message = "可用次数至少 1 次")
            Integer uses,
            @Min(value = 1, message = "有效期至少 1 小时")
            @Max(value = 720, message = "有效期最长 720 小时（30 天）")
            Integer expireHours) {
    }

    public record RevokeRequest(@NotBlank(message = "缺少邀请码") String code) {
    }

    /**
     * 对外视图。code 明文要给商家，他得复制发给用户；
     * 生成人只回昵称。
     */
    public record InviteCodeView(String code, String status, Integer maxUses, Integer usedCount,
                                 LocalDateTime expireAt, LocalDateTime lastUsedAt, LocalDateTime createdAt,
                                 Long createdByUserId, String createdByName) {

        /**
         * @param createdBy 生成人行；系统生成（created_by=0）或查不到时按 created_by 兜底显示。
         */
        static InviteCodeView of(InviteCode code, User createdBy) {
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

            return new InviteCodeView(code.getCode(), code.getStatus(),
                    code.getMaxUses(), code.getUsedCount(),
                    code.getExpireAt(), code.getLastUsedAt(), code.getCreatedAt(),
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

    /**
     * 使用者一行。只回昵称 + 脱敏手机号；使用者已被删除时昵称回「已删除用户 #id」。
     */
    public record InviteUseView(Long userId, String name, String phone, LocalDateTime usedAt) {

        static InviteUseView of(InviteUse use, User user) {
            String name;
            String phone = null;
            if (user == null) {
                // 人删了但明细还在：不能让商家误以为这条是空记录
                name = "已删除用户 #" + use.getUserId();
            } else {
                String nickName = user.getNickName();
                name = nickName == null || nickName.isBlank() ? "未填昵称" : nickName;
                phone = MaskUtil.phone(user.getPhone());
            }
            return new InviteUseView(use.getUserId(), name, phone, use.getUsedAt());
        }
    }
}

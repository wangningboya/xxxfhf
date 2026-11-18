package com.xxxfhf.meal.user;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户审核（商家端）。小程序商家页与网页后台共用这一套接口。
 *
 * 权限分工：列表只读用 user.view，通过 / 驳回 / 停用 / 改手机号用 user.audit，
 * 物理删除用单独拆出来的 user.delete（不可撤销，不该跟日常审核混一个权限）。
 */
@RestController
@RequestMapping("/admin/user")
public class UserAdminController {

    private final UserAuditService userAuditService;

    public UserAdminController(UserAuditService userAuditService) {
        this.userAuditService = userAuditService;
    }

    /** 用户列表。默认查全部，可按审核状态与昵称 / 手机号筛。 */
    @GetMapping("/list")
    @RequirePerm({Perms.USER_VIEW, Perms.USER_AUDIT})
    public ApiResponse<PageResult<UserAuditService.UserRow>> list(
            @RequestParam(required = false) String auditStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(PageResult.of(
                userAuditService.list(auditStatus, keyword, page, pageSize)));
    }

    /** 审核：approve / reject / disable / enable。reject 必须带原因。 */
    @PostMapping("/audit")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<UserAuditService.UserRow> audit(@Valid @RequestBody AuditRequest request) {
        return ApiResponse.ok(userAuditService.audit(
                UserContext.userId(), request.userId(), request.action(), request.reason()));
    }

    /** 商家代填 / 修改用户手机号。 */
    @PostMapping("/phone")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<UserAuditService.UserRow> updatePhone(@Valid @RequestBody PhoneRequest request) {
        return ApiResponse.ok(userAuditService.updatePhone(
                UserContext.userId(), request.userId(), request.phone()));
    }

    /**
     * 物理删除用户（含名下地址）。不可撤销。
     *
     * 有任意订单会返回 409 拒绝；商家账号即使直接调也删不掉（服务端只接受 customer）。
     * 返回值为连带删除的地址条数。
     */
    @PostMapping("/delete")
    @RequirePerm(Perms.USER_DELETE)
    public ApiResponse<Integer> delete(@Valid @RequestBody DeleteRequest request) {
        return ApiResponse.ok(userAuditService.delete(UserContext.userId(), request.userId()));
    }

    public record AuditRequest(
            @NotBlank(message = "缺少用户 ID") String userId,
            @NotBlank(message = "缺少审核动作") String action,
            @Size(max = 200, message = "驳回原因最多 200 字") String reason) {
    }

    public record DeleteRequest(@NotBlank(message = "缺少用户 ID") String userId) {
    }

    public record PhoneRequest(
            @NotBlank(message = "缺少用户 ID") String userId,
            @NotBlank(message = "请输入手机号")
            @Size(max = 20, message = "手机号过长") String phone) {
    }
}

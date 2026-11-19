package com.xxxfhf.meal.user;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import com.xxxfhf.meal.role.RoleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户审核（商家端）。小程序商家页与网页后台共用这一套接口。
 *
 * 权限分工：列表只读用 user.view，通过 / 驳回 / 停用 / 改手机号 / 指定为商家用 user.audit，
 * 物理删除用单独拆出来的 user.delete（不可撤销，不该跟日常审核混一个权限）。
 */
@RestController
@RequestMapping("/admin/user")
public class UserAdminController {

    private final UserAuditService userAuditService;
    private final RoleService roleService;

    public UserAdminController(UserAuditService userAuditService, RoleService roleService) {
        this.userAuditService = userAuditService;
        this.roleService = roleService;
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
     * 返回值为连带删除的条数。
     */
    @PostMapping("/delete")
    @RequirePerm(Perms.USER_DELETE)
    public ApiResponse<Integer> delete(@Valid @RequestBody DeleteRequest request) {
        return ApiResponse.ok(userAuditService.delete(UserContext.userId(), request.userId()));
    }

    /**
     * 指定为商家（需求 4.17）。把已有微信顾客就地升级为商家账号：
     * 设后台账号 / 密码 / 角色，审核状态一律置为 approved。
     *
     * <p>服务端只接受 customer：已经是商家的行再调直接报错。openid 等原有字段不动，
     * 升级后该用户的微信打开小程序即以商家身份进入。
     */
    @PostMapping("/promote")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<Void> promote(@Valid @RequestBody PromoteRequest request) {
        userAuditService.promote(UserContext.userId(), request.userId(),
                request.username(), request.password(), request.roleIds());
        return ApiResponse.ok();
    }

    /** 角色下拉选项（仅 id + 名称）。挂 user.audit：能指定商家的人不一定能管角色。 */
    @GetMapping("/role-options")
    @RequirePerm(Perms.USER_AUDIT)
    public ApiResponse<List<RoleService.RoleOption>> roleOptions() {
        return ApiResponse.ok(roleService.options());
    }

    /**
     * 商家账号列表（「用户管理 → 商家用户」页签）。
     *
     * <p>查看挂 user.view / user.audit 任一（与页面菜单可见性一致）；
     * 分配角色与解除锁定走 /admin/role/* 那两个接口（D-28 放宽到 user.audit），
     * 这里只出数据。实现复用 RoleService.accounts()，与「账号授权」同一份数据口径，
     * 不另造一份聚合逻辑。
     */
    @GetMapping("/merchants")
    @RequirePerm({Perms.USER_VIEW, Perms.USER_AUDIT})
    public ApiResponse<List<RoleService.AccountRow>> merchants() {
        return ApiResponse.ok(roleService.accounts());
    }

    /**
     * 查看某个用户的完整手机号。
     *
     * <p>列表一律给掩码，这里是唯一的明文出口：一次一个用户、写一条操作记录。
     * 权限单独拆成 user.phone.view —— 能审核用户不等于能拿到所有人的号码。
     */
    @GetMapping("/{userId}/phone")
    @RequirePerm(Perms.USER_PHONE_VIEW)
    public ApiResponse<String> phone(@PathVariable String userId) {
        return ApiResponse.ok(userAuditService.fullPhone(UserContext.userId(), userId));
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

    public record PromoteRequest(
            @NotBlank(message = "缺少用户 ID") String userId,
            @NotBlank(message = "请输入登录账号")
            @Size(min = 2, max = 64, message = "登录账号长度须在 2 ~ 64 字符之间") String username,
            @NotBlank(message = "请输入登录密码")
            @Size(min = 6, max = 64, message = "登录密码长度须在 6 ~ 64 位之间") String password,
            @NotEmpty(message = "请至少选择一个后台角色") List<String> roleIds) {
    }
}

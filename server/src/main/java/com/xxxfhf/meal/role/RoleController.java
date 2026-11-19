package com.xxxfhf.meal.role;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与权限分配。仅网页后台使用。
 *
 * 类级默认 role.manage；例外见方法注解——分配角色与解除锁定放宽为
 * role.manage / user.audit 任一命中（需求 4.17 商家用户页签，决策 D-28），
 * 「用户管理 → 商家用户」与这里的「账号授权」共用同一套接口。
 */
@RestController
@RequestMapping("/admin/role")
@RequirePerm(Perms.ROLE_MANAGE)
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /** 角色列表，带每个角色的在用账号数。 */
    @GetMapping("/list")
    public ApiResponse<List<RoleService.RoleView>> list() {
        return ApiResponse.ok(roleService.list());
    }

    /** 可分配的权限项清单（18 项，含 5 个只读）。 */
    @GetMapping("/permissions")
    public ApiResponse<List<RoleService.PermissionItem>> permissions() {
        return ApiResponse.ok(roleService.permissionItems());
    }

    /** 新增或编辑角色。不传 id 为新增；内置角色只能改名。 */
    @PostMapping("/save")
    public ApiResponse<RoleService.RoleView> save(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.ok(roleService.save(
                UserContext.userId(), request.id(), request.name(), request.permissions()));
    }

    /** 删除角色。内置角色、仍在使用的角色都会被拒绝并说明原因。 */
    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody DeleteRequest request) {
        roleService.delete(UserContext.userId(), request.id());
        return ApiResponse.ok(null);
    }

    /** 商家账号列表，用于分配角色。 */
    @GetMapping("/accounts")
    public ApiResponse<List<RoleService.AccountRow>> accounts() {
        return ApiResponse.ok(roleService.accounts());
    }

    /**
     * 给商家账号分配角色，至少保留一个。
     *
     * <p>权限放宽为 role.manage / user.audit 任一命中（D-28）：能审用户的人
     * 在「用户管理 → 商家用户」页签也要能用，否则按钮看得见点不动。
     * 方法级注解覆盖类级注解（拦截器先取方法注解）。
     */
    @PostMapping("/assign")
    @RequirePerm({Perms.ROLE_MANAGE, Perms.USER_AUDIT})
    public ApiResponse<RoleService.AccountRow> assign(@Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(roleService.assign(
                UserContext.userId(), request.userId(), request.roleIds()));
    }

    /** 解除商家账号的登录锁定（密码连续失败 3 次会锁 30 分钟，这里可提前解除）。权限同 assign，见 D-28。 */
    @PostMapping("/unlock")
    @RequirePerm({Perms.ROLE_MANAGE, Perms.USER_AUDIT})
    public ApiResponse<RoleService.AccountRow> unlock(@Valid @RequestBody UnlockRequest request) {
        return ApiResponse.ok(roleService.unlock(UserContext.userId(), request.userId()));
    }

    public record SaveRequest(
            Long id,
            @NotBlank(message = "请填写角色名称")
            @Size(max = 64, message = "角色名称最多 64 个字") String name,
            List<String> permissions) {
    }

    public record DeleteRequest(@NotNull(message = "缺少角色 ID") Long id) {
    }

    public record AssignRequest(
            @NotNull(message = "缺少账号 ID") Long userId,
            List<Long> roleIds) {
    }

    public record UnlockRequest(@NotNull(message = "缺少账号 ID") Long userId) {
    }
}

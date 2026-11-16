package com.xxxfhf.meal.role;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与权限分配。仅网页后台使用，全部需要 role.manage。
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

    /** 可分配的权限项清单（15 项，含 4 个只读）。 */
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

    /** 给商家账号分配角色，至少保留一个。 */
    @PostMapping("/assign")
    public ApiResponse<RoleService.AccountRow> assign(@Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(roleService.assign(
                UserContext.userId(), request.userId(), request.roleIds()));
    }

    public record SaveRequest(
            String id,
            @NotBlank(message = "请填写角色名称")
            @Size(max = 64, message = "角色名称最多 64 个字") String name,
            List<String> permissions) {
    }

    public record DeleteRequest(@NotBlank(message = "缺少角色 ID") String id) {
    }

    public record AssignRequest(
            @NotBlank(message = "缺少账号 ID") String userId,
            List<String> roleIds) {
    }
}

package com.xxxfhf.meal.region;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 区域管理（商家端）+ 用户端可用的区域列表。
 *
 * 用户端走 /region/list（登录即可，不需要后台权限）——用户新增地址时要选区域，
 * 但用户不该看到停用的区域，也不该能改区域。
 */
@RestController
public class RegionController {

    private final RegionService regionService;

    public RegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    /** 用户端：启用中的区域。 */
    @GetMapping("/region/list")
    public ApiResponse<List<Region>> listForUser() {
        return ApiResponse.ok(regionService.listEnabled());
    }

    /** 商家端：全部区域。 */
    @GetMapping("/admin/region/list")
    @RequirePerm(Perms.REGION_MANAGE)
    public ApiResponse<List<Region>> listForAdmin() {
        return ApiResponse.ok(regionService.listAll());
    }

    @PostMapping("/admin/region/save")
    @RequirePerm(Perms.REGION_MANAGE)
    public ApiResponse<Region> save(@RequestBody RegionService.RegionCommand command) {
        return ApiResponse.ok(regionService.save(command));
    }

    @PostMapping("/admin/region/status")
    @RequirePerm(Perms.REGION_MANAGE)
    public ApiResponse<Region> updateStatus(@Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(regionService.updateStatus(request.regionId(), request.status()));
    }

    /** 有地址或已被订单引用时返回 409，只能停用。 */
    @PostMapping("/admin/region/delete")
    @RequirePerm(Perms.REGION_MANAGE)
    public ApiResponse<Void> delete(@Valid @RequestBody IdRequest request) {
        regionService.delete(request.regionId());
        return ApiResponse.ok();
    }

    public record StatusRequest(
            @NotNull(message = "缺少区域 ID") Long regionId,
            @NotBlank(message = "缺少目标状态") String status) {
    }

    public record IdRequest(@NotNull(message = "缺少区域 ID") Long regionId) {
    }
}

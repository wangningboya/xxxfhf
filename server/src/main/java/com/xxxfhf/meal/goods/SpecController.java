package com.xxxfhf.meal.goods;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 规格组维护。按需求 4.15，**只在网页后台做**，小程序内只能选用（走 /list-enabled）。
 *
 * 权限沿用 COMBO_MANAGE：规格组是盒饭的一部分，单独拆一个权限项会让「谁能配盒饭」
 * 这句话变得有歧义。
 */
@RestController
@RequestMapping("/admin/spec")
public class SpecController {

    private final SpecService specService;

    public SpecController(SpecService specService) {
        this.specService = specService;
    }

    /** 全部规格组（含停用），维护页用。 */
    @GetMapping("/list")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<List<SpecGroup>> list() {
        return ApiResponse.ok(specService.list());
    }

    /** 只含启用中的组，盒饭编辑页的选择器用。 */
    @GetMapping("/list-enabled")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<List<SpecGroup>> listEnabled() {
        return ApiResponse.ok(specService.listEnabled());
    }

    /** 停用前的二次确认：列出会被连带解除引用的盒饭。 */
    @GetMapping("/affected")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<List<SpecService.ComboBrief>> affected(@RequestParam String specGroupId) {
        return ApiResponse.ok(specService.affectedCombos(specGroupId));
    }

    @PostMapping("/save")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<SpecGroup> save(@RequestBody SpecService.SpecCommand command) {
        return ApiResponse.ok(specService.save(command));
    }

    /**
     * 启用 / 停用。
     *
     * 停用且被盒饭引用时，不带 confirmUnbind 会返回 409 + 受影响清单；
     * 商家在弹窗里确认后，前端带 confirmUnbind=true 再提交一次。
     */
    @PostMapping("/status")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<SpecGroup> updateStatus(@Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(specService.updateStatus(
                request.specGroupId(), request.status(),
                Boolean.TRUE.equals(request.confirmUnbind())));
    }

    @PostMapping("/delete")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<Void> delete(@Valid @RequestBody IdRequest request) {
        specService.delete(request.specGroupId());
        return ApiResponse.ok();
    }

    public record StatusRequest(
            @NotBlank(message = "缺少规格组 ID") String specGroupId,
            @NotBlank(message = "缺少目标状态") String status,
            Boolean confirmUnbind) {
    }

    public record IdRequest(@NotBlank(message = "缺少规格组 ID") String specGroupId) {
    }
}

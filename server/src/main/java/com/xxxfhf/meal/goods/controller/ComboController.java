package com.xxxfhf.meal.goods.controller;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.goods.domain.Combo;
import com.xxxfhf.meal.goods.service.ComboService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 盒饭维护（商家端）。
 *
 * 价格入参是整数**分**：前端在提交前把商家填的元换算成分，服务端不接受小数。
 */
@RestController
@RequestMapping("/admin/combo")
public class ComboController {

    private final ComboService comboService;

    public ComboController(ComboService comboService) {
        this.comboService = comboService;
    }

    @GetMapping("/list")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<List<Combo>> list(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String categoryCode) {
        return ApiResponse.ok(comboService.list(keyword, status, categoryCode));
    }

    @GetMapping("/detail")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<Combo> detail(@RequestParam Long comboId) {
        return ApiResponse.ok(comboService.detail(comboId));
    }

    @PostMapping("/save")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<Combo> save(@RequestBody ComboService.ComboCommand command) {
        return ApiResponse.ok(comboService.save(command));
    }

    /** 上架 / 下架。下架不影响已下单的订单（订单存的是快照）。 */
    @PostMapping("/status")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<Combo> updateStatus(@Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(comboService.updateStatus(request.comboId(), request.status()));
    }

    /** 删除。历史订单引用过的只能下架。 */
    @PostMapping("/delete")
    @RequirePerm(Perms.COMBO_MANAGE)
    public ApiResponse<Void> delete(@Valid @RequestBody IdRequest request) {
        comboService.delete(request.comboId());
        return ApiResponse.ok();
    }

    public record StatusRequest(
            @NotNull(message = "缺少盒饭 ID") Long comboId,
            @NotBlank(message = "缺少目标状态") String status) {
    }

    public record IdRequest(@NotNull(message = "缺少盒饭 ID") Long comboId) {
    }
}

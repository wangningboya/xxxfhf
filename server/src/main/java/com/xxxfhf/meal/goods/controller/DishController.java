package com.xxxfhf.meal.goods.controller;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.goods.domain.Dish;
import com.xxxfhf.meal.goods.service.DishService;
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
 * 菜品库维护（商家端）。小程序商家页与网页后台共用。
 *
 * 查询也要权限：菜品库属于经营配置，不是公开数据。
 */
@RestController
@RequestMapping("/admin/dish")
public class DishController {

    private final DishService dishService;

    public DishController(DishService dishService) {
        this.dishService = dishService;
    }

    /** 列表。status 留空查全部。 */
    @GetMapping("/list")
    @RequirePerm(Perms.DISH_MANAGE)
    public ApiResponse<List<Dish>> list(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status) {
        return ApiResponse.ok(dishService.list(keyword, status));
    }

    @PostMapping("/save")
    @RequirePerm(Perms.DISH_MANAGE)
    public ApiResponse<Dish> save(@RequestBody DishService.DishCommand command) {
        return ApiResponse.ok(dishService.save(command));
    }

    /** 启用 / 停用。停用不会影响已经把它配进盒饭的那些盒饭。 */
    @PostMapping("/status")
    @RequirePerm(Perms.DISH_MANAGE)
    public ApiResponse<Dish> updateStatus(@Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(dishService.updateStatus(request.dishId(), request.status()));
    }

    /** 删除。被盒饭引用时返回 409 并点名是哪几个盒饭。 */
    @PostMapping("/delete")
    @RequirePerm(Perms.DISH_MANAGE)
    public ApiResponse<Void> delete(@Valid @RequestBody IdRequest request) {
        dishService.delete(request.dishId());
        return ApiResponse.ok();
    }

    public record StatusRequest(
            @NotNull(message = "缺少菜品 ID") Long dishId,
            @NotBlank(message = "缺少目标状态") String status) {
    }

    public record IdRequest(@NotNull(message = "缺少菜品 ID") Long dishId) {
    }
}

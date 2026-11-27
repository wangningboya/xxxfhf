package com.xxxfhf.meal.menu.controller;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.menu.domain.DateParam;
import com.xxxfhf.meal.menu.service.MenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日菜单管理（商家端）。手机与网页后台共用。
 */
@RestController
@RequestMapping("/admin/menu")
public class MenuAdminController {

    private final MenuService menuService;

    public MenuAdminController(MenuService menuService) {
        this.menuService = menuService;
    }

    /** 查看某日菜单（草稿也返回）。serveDate 留空按明天。 */
    @GetMapping("/view")
    @RequirePerm({Perms.MENU_MANAGE, Perms.ORDER_VIEW})
    public ApiResponse<MenuService.AdminMenuView> view(@RequestParam(required = false) String serveDate) {
        LocalDate date = DateParam.parse(serveDate, "供餐日期");
        return ApiResponse.ok(menuService.adminView(date == null ? LocalDate.now().plusDays(1) : date));
    }

    /** 保存某日菜单：勾选盒饭 + 设限量。items 为空表示这一天不供应。 */
    @PostMapping("/save")
    @RequirePerm(Perms.MENU_MANAGE)
    public ApiResponse<Void> save(@RequestBody SaveRequest request) {
        LocalDate date = DateParam.parse(request.serveDate(), "供餐日期");
        menuService.save(date, request.items());
        return ApiResponse.ok();
    }

    /** 复制上一份：返回该日期之前最近一份菜单的盒饭与限量，前端预填后用 save 提交。 */
    @GetMapping("/copy")
    @RequirePerm(Perms.MENU_MANAGE)
    public ApiResponse<List<MenuService.MenuInput>> copy(@RequestParam String serveDate) {
        return ApiResponse.ok(menuService.copyFrom(DateParam.parse(serveDate, "供餐日期")));
    }

    /** 发布 / 撤下。撤下后用户端的「后天」Tab 会消失。 */
    @PostMapping("/publish")
    @RequirePerm(Perms.MENU_MANAGE)
    public ApiResponse<Void> publish(@RequestBody PublishRequest request) {
        LocalDate date = DateParam.parse(request.serveDate(), "供餐日期");
        menuService.publish(date, Boolean.TRUE.equals(request.published()));
        return ApiResponse.ok();
    }

    /**
     * 一键停业 / 取消停业。
     *
     * 作用于指定的供餐日，**截单后也能操作** —— 「明天不做了」经常是截单之后才决定的。
     */
    @PostMapping("/close")
    @RequirePerm(Perms.MENU_MANAGE)
    public ApiResponse<Void> close(@RequestBody CloseRequest request) {
        LocalDate date = DateParam.parse(request.serveDate(), "供餐日期");
        menuService.setClosed(date, Boolean.TRUE.equals(request.closed()));
        return ApiResponse.ok();
    }

    public record SaveRequest(String serveDate, List<MenuService.MenuInput> items) {
    }

    public record PublishRequest(String serveDate, Boolean published) {
    }

    public record CloseRequest(String serveDate, Boolean closed) {
    }
}

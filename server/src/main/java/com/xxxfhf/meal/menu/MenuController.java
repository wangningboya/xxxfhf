package com.xxxfhf.meal.menu;

import com.xxxfhf.meal.auth.AccessGuard;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户端菜单浏览。
 *
 * 需要**已准入**（不是已通过审核）：待审核用户照样能看菜单，只是下不了单 —— 这是需求
 * 「看得到但下不了单」的直接实现。未准入的人连菜单都不该看到。
 */
@RestController
public class MenuController {

    private final MenuService menuService;
    private final AccessGuard accessGuard;

    public MenuController(MenuService menuService, AccessGuard accessGuard) {
        this.menuService = menuService;
        this.accessGuard = accessGuard;
    }

    /**
     * 按供餐日取菜单。serveDate 留空按明天。
     *
     * 只允许明天与后天：更远的日期一律拒绝，与「用户端最多展示两个供餐日」一致。
     */
    @GetMapping("/menu")
    public ApiResponse<MenuService.UserMenuView> menu(@RequestParam(required = false) String serveDate) {
        accessGuard.requireAccess(UserContext.userId());
        LocalDate date = DateParam.parse(serveDate, "供餐日期");
        return ApiResponse.ok(menuService.userView(date));
    }

    /** 全部菜单（仅上架），带「明日供应」标记。只浏览，不提供加购入口。 */
    @GetMapping("/combo/all")
    public ApiResponse<List<MenuService.ComboAllItem>> allCombos() {
        accessGuard.requireAccess(UserContext.userId());
        return ApiResponse.ok(menuService.allCombos());
    }
}

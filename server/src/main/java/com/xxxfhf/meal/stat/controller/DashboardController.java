package com.xxxfhf.meal.stat.controller;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.stat.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台工作台。登录后第一屏的数据源。
 *
 * 权限用「订单查看 / 订单处理 / 用户审核」任一命中即可：工作台只是把各自有权限的
 * 数字摆出来，不该要求更高权限。细粒度控制留给各列表页自己的接口。
 */
@RestController
@RequestMapping("/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT, Perms.USER_AUDIT})
    public ApiResponse<DashboardService.DashboardView> summary(
            @RequestParam(required = false) String serveDate) {
        return ApiResponse.ok(dashboardService.summary(serveDate));
    }
}

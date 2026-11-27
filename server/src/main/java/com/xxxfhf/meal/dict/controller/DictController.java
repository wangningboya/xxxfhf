package com.xxxfhf.meal.dict.controller;

import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.dict.service.DictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 前端取字典。小程序与网页后台进应用时各取一次，本地缓存用。
 *
 * 不做权限校验：字典里只有「pending_accept 叫待接单」这类展示信息，
 * 不含任何业务数据；加权限反而会让「订单查看」以外的角色看不到状态名。
 */
@RestController
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    /** 全部启用中的字典：`{ "order_status": [{...}], "pay_status": [{...}] }` */
    @GetMapping("/dict")
    public ApiResponse<Map<String, List<DictService.ItemView>>> all() {
        return ApiResponse.ok(dictService.clientDict());
    }
}

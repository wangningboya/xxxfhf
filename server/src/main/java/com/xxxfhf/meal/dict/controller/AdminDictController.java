package com.xxxfhf.meal.dict.controller;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.dict.service.DictService;
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
 * 字典维护（后台）。
 *
 * 内置类型与内置项的约束在 Service 里判：类型不可删、项不可增删、
 * 存值不可改。这里只做参数校验与转发。
 */
@RestController
@RequestMapping("/admin/dict")
@RequirePerm(Perms.DICT_MANAGE)
public class AdminDictController {

    private final DictService dictService;

    public AdminDictController(DictService dictService) {
        this.dictService = dictService;
    }

    @GetMapping("/types")
    public ApiResponse<List<DictService.TypeView>> types() {
        return ApiResponse.ok(dictService.listTypes());
    }

    @PostMapping("/type/save")
    public ApiResponse<DictService.TypeView> saveType(@Valid @RequestBody TypeRequest request) {
        return ApiResponse.ok(dictService.saveType(
                new DictService.SaveTypeCommand(request.typeCode(), request.typeName(), request.remark())));
    }

    @PostMapping("/type/delete")
    public ApiResponse<Void> deleteType(@Valid @RequestBody TypeIdRequest request) {
        dictService.deleteType(request.typeCode());
        return ApiResponse.ok();
    }

    @GetMapping("/items")
    public ApiResponse<List<DictService.ItemView>> items(@RequestParam String typeCode) {
        return ApiResponse.ok(dictService.listItems(typeCode));
    }

    @PostMapping("/item/save")
    public ApiResponse<DictService.ItemView> saveItem(@Valid @RequestBody ItemRequest request) {
        return ApiResponse.ok(dictService.saveItem(new DictService.SaveItemCommand(
                request.id(), request.typeCode(), request.itemValue(), request.itemLabel(),
                request.tagType(), request.sort(), request.status())));
    }

    @PostMapping("/item/delete")
    public ApiResponse<Void> deleteItem(@Valid @RequestBody ItemIdRequest request) {
        dictService.deleteItem(request.id());
        return ApiResponse.ok();
    }

    @PostMapping("/item/status")
    public ApiResponse<DictService.ItemView> itemStatus(@Valid @RequestBody ItemStatusRequest request) {
        return ApiResponse.ok(dictService.setItemStatus(request.id(), request.status()));
    }

    public record TypeRequest(
            @NotBlank(message = "请填写类型编码") String typeCode,
            @NotBlank(message = "请填写类型名称") String typeName,
            String remark) {
    }

    public record TypeIdRequest(@NotBlank(message = "缺少类型编码") String typeCode) {
    }

    public record ItemRequest(
            Long id,
            @NotBlank(message = "缺少类型编码") String typeCode,
            @NotBlank(message = "请填写字典值") String itemValue,
            @NotBlank(message = "请填写显示名") String itemLabel,
            String tagType,
            Integer sort,
            String status) {
    }

    public record ItemIdRequest(@NotNull(message = "缺少字典项 ID") Long id) {
    }

    public record ItemStatusRequest(
            @NotNull(message = "缺少字典项 ID") Long id,
            @NotBlank(message = "缺少目标状态") String status) {
    }
}

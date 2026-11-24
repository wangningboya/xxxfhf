package com.xxxfhf.meal.print;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 蓝牙标签打印的两个接口。
 *
 * <p>权限一律用 {@code order.view}：打印只是把屏幕上已有的信息落到纸上，看单的人就该能打单。
 * 打包岗常只有看单权限，若另设 {@code order.print}，上线时所有自定义角色都要重新勾选一遍
 * （需求第十章）。
 *
 * <p>排版不在服务端：这里给的是「已排好内容的一页纸」，坐标在端上两套 layout 文件里。
 */
@RestController
@RequestMapping("/admin/print")
public class PrintController {

    private final PrintService printService;

    public PrintController(PrintService printService) {
        this.printService = printService;
    }

    /**
     * 取标签数据。返回的 {@code pages} **已展开到张**：A×2 就是两条。
     *
     * <p>一次请求取全部选中订单，不是逐单取：批量 10 单只发一次，不会中途因某单失败打断；
     * 也避免逐单取详情触发风控。
     */
    @PostMapping("/labels")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<PrintService.LabelsResult> labels(@Valid @RequestBody LabelsRequest request) {
        return ApiResponse.ok(printService.labels(request.orderIds()));
    }

    /**
     * 上报一张纸的结果。**一张一报，不是一批一报**。
     *
     * <p>每张打印回调结束就发，含失败张。这样小程序被杀、断电、切后台时，
     * 已经出的纸都有记录；攒到最后一次提交的话，中断就全丢了。
     */
    @PostMapping("/page")
    @RequirePerm({Perms.ORDER_VIEW, Perms.ORDER_ACCEPT})
    public ApiResponse<Void> page(@Valid @RequestBody PageRequest request) {
        printService.recordPage(UserContext.userId(),
                new PrintService.ReportCommand(request.batchNo(), request.orderId(), request.pageKey(),
                        request.template(), request.result(), request.errCode(), request.deviceName()));
        return ApiResponse.ok();
    }

    public record LabelsRequest(@NotEmpty(message = "请选择要打印的订单") List<Long> orderIds) {
    }

    /**
     * @param pageKey    页标识，如 o1024-i1-c2；与 orderId 一起做幂等
     * @param result     success / fail
     * @param errCode    失败时的 SDK 错误码，成功可不传
     * @param deviceName 打印机广播名，多台设备时用于排查
     */
    public record PageRequest(
            String batchNo,
            @NotNull(message = "缺少订单 ID") Long orderId,
            @NotNull(message = "缺少页标识") String pageKey,
            String template,
            @NotNull(message = "缺少打印结果") String result,
            Integer errCode,
            String deviceName) {
    }
}

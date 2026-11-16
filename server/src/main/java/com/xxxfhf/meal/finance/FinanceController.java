package com.xxxfhf.meal.finance;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.menu.DateParam;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 对账视图（商家端）。
 *
 * 查看受 `finance.view`，导出受 `prep.export` —— 与备菜导出同一个「能拿走明细」的权限，
 * 不为了少给一个权限项而让两处口径分裂。
 */
@RestController
@RequestMapping("/admin/finance")
public class FinanceController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FinanceService financeService;

    public FinanceController(FinanceService financeService) {
        this.financeService = financeService;
    }

    /** 四个数（外加待退款）按供餐日汇总。serveDate 留空按明天。 */
    @GetMapping
    @RequirePerm(Perms.FINANCE_VIEW)
    public ApiResponse<FinanceService.FinanceSummary> summary(
            @RequestParam(required = false) String serveDate) {
        return ApiResponse.ok(financeService.summary(DateParam.parse(serveDate, "供餐日期")));
    }

    /** 点数字下钻：列出该口径对应的订单。 */
    @GetMapping("/detail")
    @RequirePerm(Perms.FINANCE_VIEW)
    public ApiResponse<List<FinanceService.FinanceRow>> detail(
            @RequestParam(required = false) String serveDate,
            @RequestParam String bucket) {
        return ApiResponse.ok(financeService.detail(
                DateParam.parse(serveDate, "供餐日期"), bucket));
    }

    @GetMapping("/export")
    @RequirePerm(Perms.PREP_EXPORT)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String serveDate) {
        LocalDate date = DateParam.parse(serveDate, "供餐日期");
        byte[] body = financeService.export(date);
        LocalDate fileDate = date == null ? LocalDate.now().plusDays(1) : date;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("对账_" + fileDate + ".xlsx", StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(XLSX)
                .body(body);
    }
}

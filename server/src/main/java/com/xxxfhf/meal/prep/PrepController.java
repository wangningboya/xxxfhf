package com.xxxfhf.meal.prep;

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

/**
 * 备菜统计（商家端）。小程序内只读，网页后台可导出 Excel。
 *
 * 两个权限分开：`prep.view` 给厨房看数，`prep.export` 给能拿到采购清单的人。
 */
@RestController
@RequestMapping("/admin/prep")
public class PrepController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PrepService prepService;

    public PrepController(PrepService prepService) {
        this.prepService = prepService;
    }

    /** 备菜视图。serveDate 留空按明天。 */
    @GetMapping
    @RequirePerm(Perms.PREP_VIEW)
    public ApiResponse<PrepService.PrepView> view(@RequestParam(required = false) String serveDate) {
        return ApiResponse.ok(prepService.view(DateParam.parse(serveDate, "供餐日期")));
    }

    /** 导出两个 Sheet：菜品汇总、区域汇总。 */
    @GetMapping("/export")
    @RequirePerm(Perms.PREP_EXPORT)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String serveDate) {
        LocalDate date = DateParam.parse(serveDate, "供餐日期");
        byte[] body = prepService.export(date);
        LocalDate fileDate = date == null ? LocalDate.now().plusDays(1) : date;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("备菜_" + fileDate + ".xlsx", StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(XLSX)
                .body(body);
    }
}

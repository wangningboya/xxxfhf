package com.xxxfhf.meal.audit;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 操作记录查看（后台「系统设置 → 操作记录」）。
 *
 * 只读：流水表不提供任何写接口，写入只发生在业务动作里。
 */
@RestController
@RequestMapping("/admin/audit-log")
@RequirePerm(Perms.AUDIT_VIEW)
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/list")
    public ApiResponse<PageResult<AuditService.AuditRow>> list(
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(auditService.list(tableName, action, operator, keyword,
                startDate, endDate, page, pageSize));
    }

    /** 动作筛选下拉的选项（码 + 中文名 + 归属对象），与埋点常量同源 */
    @GetMapping("/actions")
    public ApiResponse<List<AuditActions.Action>> actions() {
        return ApiResponse.ok(auditService.actions());
    }
}

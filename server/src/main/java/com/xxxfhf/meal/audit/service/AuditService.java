package com.xxxfhf.meal.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.audit.domain.AuditActions;
import com.xxxfhf.meal.audit.domain.AuditLog;
import com.xxxfhf.meal.audit.mapper.AuditLogMapper;
import com.xxxfhf.meal.common.PageResult;
import com.xxxfhf.meal.menu.domain.DateParam;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作记录：写一条 + 按页查。
 *
 * <p>为什么是<b>手工埋点</b>而不是全局切面：切面拿不到改前的值（要额外查一次库），
 * 也写不出人话 summary，最后只能落一堆 SQL 快照 —— 那种日志事后没人看得懂。
 * 这些动作总共二十来个，逐个写反而清楚。
 *
 * <p>写入失败绝不抛异常：留痕是副产品，不能因为它把「审核通过」本身搞失败。
 * 出错只记 error 日志，业务照常提交。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 与 audit_log.summary 的列宽一致，超了会在 MySQL 严格模式下直接报错 */
    private static final int MAX_SUMMARY = 500;

    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;

    public AuditService(AuditLogMapper auditLogMapper, UserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
    }

    /**
     * 按当前请求上下文记一条。操作人、来源自动取。
     *
     * @param tableName 对象表，用 {@link AuditActions#TABLE_ADDRESS} 这类常量
     * @param recordId  对象主键
     * @param action    动作码，用 {@link AuditActions} 里的常量
     * @param summary   人读的一句话，写清「哪条、做了什么、关键值前后是什么」
     */
    public void record(String tableName, Long recordId, String action, String summary) {
        record(tableName, recordId, action, summary, AuditHelper.actor(), AuditHelper.source());
    }

    /**
     * 显式指定操作人与来源，给定时任务用（那里没有登录态）。
     *
     * 超时自动同意这类「系统代做」的动作必须走这个重载，否则会被记成 task 来源但操作人是 system，
     * 而真实原因是「商家没处理」，看日志的人需要一眼分辨。
     */
    @Transactional
    public void record(String tableName, Long recordId, String action, String summary,
                       Long operatorId, String source) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTableName(tableName);
            entry.setRecordId(recordId == null ? 0L : recordId);
            entry.setAction(action);
            entry.setSummary(truncate(summary));
            entry.setOperatorId(operatorId == null ? AuditHelper.SYSTEM : operatorId);
            entry.setOperatorName(operatorName(entry.getOperatorId()));
            entry.setSource(source == null ? AuditHelper.SOURCE_TASK : source);
            entry.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (RuntimeException e) {
            // 留痕失败不影响业务，只留一行 error 供排查
            log.error("写操作记录失败 table={} record={} action={}", tableName, recordId, action, e);
        }
    }

    /**
     * 操作人名称快照。
     *
     * 每次写入查一次用户表（主键查，代价可忽略）：存快照而不是读时 join，
     * 是为了账号改名或被删之后历史记录仍读得出当时是谁。
     *
     * <p>公开给 {@code OrderStatusLogService} 复用：订单流转记录同样要存快照，
     * 「昵称优先、其次账号、再退 id」这套口径只能有一份，两份迟早会分叉。
     */
    public String operatorName(Long operatorId) {
        if (operatorId == null || AuditHelper.SYSTEM.equals(operatorId)) {
            return "系统";
        }
        User user = userMapper.selectById(operatorId);
        if (user == null) {
            return String.valueOf(operatorId);
        }
        if (user.getNickName() != null && !user.getNickName().isBlank()) {
            return user.getNickName();
        }
        return user.getUsername() == null ? String.valueOf(operatorId) : user.getUsername();
    }

    /* ==================== 查询 ==================== */

    /**
     * 操作记录列表，时间倒序。
     *
     * @param tableName 对象类型：address / user，空则全部（订单域已迁至 order_status_log）
     * @param action    动作码，空则全部
     * @param operator  操作人名称模糊匹配
     * @param keyword   说明或对象 ID 模糊匹配
     */
    public PageResult<AuditRow> list(String tableName, String action, String operator, String keyword,
                                     String startDateRaw, String endDateRaw, int page, int pageSize) {
        LocalDate start = DateParam.parse(startDateRaw, "开始日期");
        LocalDate end = DateParam.parse(endDateRaw, "结束日期");

        String op = operator == null ? "" : operator.trim();
        String kw = keyword == null ? "" : keyword.trim();

        LambdaQueryWrapper<AuditLog> query = Wrappers.<AuditLog>lambdaQuery()
                .eq(tableName != null && !tableName.isBlank(), AuditLog::getTableName, tableName)
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .like(!op.isEmpty(), AuditLog::getOperatorName, op)
                .and(!kw.isEmpty(), w -> w.like(AuditLog::getSummary, kw)
                        .or().like(AuditLog::getRecordId, kw))
                // 结束日期含当天：用 < 次日零点，避免 "2026-11-18 10:00" 被 <= 当天零点漏掉
                .ge(start != null, AuditLog::getCreatedAt, start == null ? null : start.atStartOfDay())
                .lt(end != null, AuditLog::getCreatedAt, end == null ? null : end.plusDays(1).atStartOfDay())
                .orderByDesc(AuditLog::getCreatedAt);

        Page<AuditLog> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        IPage<AuditLog> result = auditLogMapper.selectPage(pager, query);

        List<AuditRow> rows = new ArrayList<>(result.getRecords().size());
        for (AuditLog entry : result.getRecords()) {
            rows.add(new AuditRow(entry.getId(), entry.getTableName(), tableLabel(entry.getTableName()),
                    entry.getRecordId(), entry.getAction(), AuditActions.label(entry.getAction()),
                    entry.getSummary(), entry.getOperatorId(), entry.getOperatorName(),
                    entry.getSource(), sourceLabel(entry.getSource()), entry.getCreatedAt()));
        }
        return new PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 筛选下拉：对象类型 / 动作清单都由服务端下发，前端不硬编码文案 */
    public List<AuditActions.Action> actions() {
        return AuditActions.all();
    }

    /** 对象表中文名：清单收在 {@link AuditActions#tableLabelOf}，这里只做转调，不再维护第二份 switch */
    static String tableLabel(String tableName) {
        return AuditActions.tableLabelOf(tableName);
    }

    /** 来源中文名。订单流转记录的时间轴也用同一套口径，故对外公开。 */
    public static String sourceLabel(String source) {
        if (source == null) {
            return "";
        }
        return switch (source) {
            case AuditHelper.SOURCE_ADMIN -> "网页后台";
            case AuditHelper.SOURCE_MINIPROGRAM -> "小程序";
            case AuditHelper.SOURCE_TASK -> "系统任务";
            default -> source;
        };
    }

    private static String truncate(String summary) {
        String text = summary == null ? "" : summary;
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY);
    }

    /**
     * 列表行。中文名随行下发，前端不必再维护一份动作码映射。
     */
    public record AuditRow(Long id, String tableName, String tableNameLabel, Long recordId,
                           String action, String actionLabel, String summary,
                           Long operatorId, String operatorName, String source, String sourceLabel,
                           LocalDateTime createdAt) {
    }
}

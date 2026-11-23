package com.xxxfhf.meal.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.dict.DictCodes;
import com.xxxfhf.meal.dict.DictService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单状态流转记录：写一条 + 按订单查全部（需求 4.20）。
 *
 * <p>三条线（订单状态 / 收款状态 / 业务事件）共用一张表，因为前端是一条按时间排序的时间轴 ——
 * 分三张表就要三次查询再归并，还得在归并时处理同秒顺序。
 *
 * <p><b>写入失败绝不抛异常</b>：留痕是副产品，不能因为它把「接单」本身搞失败。
 * 注意 try-catch 必须写在方法体内（而不是让异常穿出事务代理），否则 Spring 会把整个事务
 * 标成 rollback-only，业务跟着一起回滚。
 *
 * <p>不提供删除与修改接口：追加型表，写了就不变。要清理按 {@code created_at} 归档。
 */
@Service
public class OrderStatusLogService {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusLogService.class);

    /** 与 order_status_log.remark 的列宽一致，超了会在 MySQL 严格模式下直接报错 */
    public static final int MAX_REMARK = 200;

    private final OrderStatusLogMapper logMapper;
    private final OrderMapper orderMapper;
    private final DictService dictService;
    private final AuditService auditService;

    public OrderStatusLogService(OrderStatusLogMapper logMapper, OrderMapper orderMapper,
                                 DictService dictService, AuditService auditService) {
        this.logMapper = logMapper;
        this.orderMapper = orderMapper;
        this.dictService = dictService;
        this.auditService = auditService;
    }

    /* ==================== 写入 ==================== */

    /**
     * 按当前请求上下文记一条（网页后台 / 小程序自动取）。
     *
     * @param orderId    订单主键
     * @param action     动作码，用 {@link OrderLogActions} 里的常量
     * @param fromStatus 变更前存值，没有前值（下单、事件）传 null
     * @param toStatus   变更后存值，事件类传 null
     * @param remark     补充说明：拒单原因、取消原因等，可为 null
     * @param at         发生时间。**必须与业务时间戳同一个 now**，两处不出现毫秒级差异
     */
    public void record(Long orderId, String action, String fromStatus, String toStatus,
                       String remark, LocalDateTime at) {
        record(orderId, action, fromStatus, toStatus, remark, null,
                AuditHelper.actor(), AuditHelper.source(), at);
    }

    /**
     * 带收款方式的记录，给 {@code pay.claim} / {@code pay.confirm} 用。
     *
     * <p>方式值与状态变更同一条记录，不另开「方式变更」：确认收款本身就是那次变更，
     * 单发一条只会让时间轴多一行讲同一件事的噪声。
     *
     * @param payMethod 见 {@link PayMethod}；非收款方式类动作传 null
     */
    public void record(Long orderId, String action, String fromStatus, String toStatus,
                       String remark, String payMethod, LocalDateTime at) {
        record(orderId, action, fromStatus, toStatus, remark, payMethod,
                AuditHelper.actor(), AuditHelper.source(), at);
    }

    /**
     * 显式指定操作人与来源，给定时任务用（那里没有登录态）。
     *
     * <p>超时自动同意、超时释放份数必须走这个重载：操作人是 {@code system}、来源是 {@code task}，
     * 事后看到「是系统干的」才知道当时商家根本没处理，这跟「商家点了同意」是两回事。
     *
     * <p>推送结果记录也走这个重载 —— 它在事务提交后执行，ThreadLocal 虽然还在，
     * 但操作人必须还原成「触发这次推送的那次操作」，不能取到别处的上下文。
     */
    public void record(Long orderId, String action, String fromStatus, String toStatus,
                       String remark, Long operatorId, String source, LocalDateTime at) {
        record(orderId, action, fromStatus, toStatus, remark, null, operatorId, source, at);
    }

    /**
     * 全参数版本。
     *
     * <p><b>注意</b>：方法内是单条 insert，自带事务，不依赖 {@code @Transactional} ——
     * 而且同类自调用本就不经过代理，加注解只会让人误以为有外层事务保护。
     */
    public void record(Long orderId, String action, String fromStatus, String toStatus,
                       String remark, String payMethod, Long operatorId, String source,
                       LocalDateTime at) {
        try {
            OrderStatusLog entry = new OrderStatusLog();
            entry.setOrderId(orderId);
            entry.setLogType(OrderLogActions.logTypeOf(action));
            entry.setAction(action);
            entry.setFromStatus(trimToNull(fromStatus));
            entry.setToStatus(trimToNull(toStatus));
            entry.setRemark(truncate(remark));
            entry.setPayMethod(trimToNull(payMethod));
            entry.setOperatorId(operatorId == null ? AuditHelper.SYSTEM : operatorId);
            entry.setOperatorName(auditService.operatorName(entry.getOperatorId()));
            entry.setSource(source == null ? AuditHelper.SOURCE_TASK : source);
            entry.setCreatedAt(at == null ? LocalDateTime.now() : at);
            logMapper.insert(entry);
        } catch (RuntimeException e) {
            // 留痕失败不影响业务，只留一行 error 供排查
            log.error("写订单流转记录失败 orderId={} action={} {} -> {}",
                    orderId, action, fromStatus, toStatus, e);
        }
    }

    /**
     * 推送结果专用：**独立事务**提交（需求 4.20 的 `event.notify` / `event.notify_fail`）。
     *
     * <p>必须单开新事务：推送在 {@code afterCommit} 里执行，此时业务事务已经提交、
     * 连接仍绑在线程上（autoCommit = false）。若直接把 insert 放进这个「已提交的事务」，
     * 语句不会自动落盘，连接归还连接池时被回滚 —— 记录**静默消失**，而且没有任何报错。
     * {@code REQUIRES_NEW} 会挂起外层同步状态、拿新连接独立提交。
     *
     * <p>反向也成立：只有业务提交了才会触发推送，所以这条记录独立提交不会出现
     * 「业务没成、日志却留下」的情况。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNotify(Long orderId, String action, String remark,
                             Long operatorId, String source) {
        record(orderId, action, null, null, remark, null, operatorId, source, LocalDateTime.now());
    }

    /* ==================== 查询 ==================== */

    /**
     * 某订单的全部流转记录，按时间正序（最早在上）。
     *
     * <p>中文名与配色在这里算好随行下发：{@code from_status} / {@code to_status} 存的是存值，
     * 显示名走字典 —— 商家把「待接单」改成「待处理」后，时间轴跟着变，不出现
     * 「详情写待处理、时间轴写待接单」。
     *
     * <p>{@code ready} 是个例外：一个状态两种叫法（自取「待取餐」、配送「配送中」），
     * 字典一项只能存一个名，这里按订单的取送方式现算，与订单详情的状态标签同源。
     */
    public List<Row> list(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.notFound("订单不存在");
        }
        String deliveryType = order.getDeliveryType();
        List<OrderStatusLog> entries = logMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, orderId)
                .orderByAsc(OrderStatusLog::getCreatedAt)
                .orderByAsc(OrderStatusLog::getId));

        List<Row> rows = new ArrayList<>(entries.size());
        for (OrderStatusLog entry : entries) {
            String typeCode = statusDictOf(entry.getLogType());
            rows.add(new Row(entry.getId(), entry.getLogType(), entry.getAction(),
                    OrderLogActions.label(entry.getAction()),
                    entry.getFromStatus(),
                    statusLabel(typeCode, entry.getFromStatus(), deliveryType),
                    dictService.tagType(typeCode, entry.getFromStatus()),
                    entry.getToStatus(),
                    statusLabel(typeCode, entry.getToStatus(), deliveryType),
                    dictService.tagType(typeCode, entry.getToStatus()),
                    entry.getRemark(),
                    entry.getPayMethod(),
                    // 快照里存的是存值，显示名现查字典：商家改了显示名时间轴跟着变；
                    // 字典项被删了回落兜底 —— 内置三项给中文，自建项原样显示当时存的 code
                    dictService.label(DictCodes.PAY_METHOD, entry.getPayMethod(),
                            PayMethod.label(entry.getPayMethod())),
                    entry.getOperatorId(), entry.getOperatorName(),
                    entry.getSource(), AuditService.sourceLabel(entry.getSource()),
                    entry.getCreatedAt()));
        }
        return rows;
    }

    /**
     * 只有订单本身流转的那条线，给用户端时间轴用。
     *
     * <p>滤掉两类：{@code event.*} 是业务事件与推送投递记录 —— 把「通知未送达｜模板未配置」
     * 摆到用户面前等于系统自己承认没做到；{@code pay.*} 是收款线，用户端详情页已有独立的
     * 「收款状态」一栏，再进时间轴是同一件事说两遍。
     *
     * <p>不在这里做归属校验：本方法只回答「这一单经历了什么」，谁能看由调用方决定
     * （用户端走 {@code OrderService} 的 {@code requireOwned}，商家端复用后台接口的权限注解）。
     */
    public List<Row> listOrderLine(Long orderId) {
        List<Row> all = list(orderId);
        List<Row> rows = new ArrayList<>(all.size());
        for (Row row : all) {
            if (OrderLogActions.TYPE_ORDER.equals(row.logType())) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 收款线用收款字典，其余（订单线、事件）用订单状态字典。 */
    private static String statusDictOf(String logType) {
        return OrderLogActions.TYPE_PAY.equals(logType) ? DictCodes.PAY_STATUS : DictCodes.ORDER_STATUS;
    }

    /**
     * 状态显示名。字典优先，缺项回退到代码里的静态文案；ready 按取送方式现算，不吃字典。
     */
    private String statusLabel(String typeCode, String status, String deliveryType) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (DictCodes.ORDER_STATUS.equals(typeCode) && OrderStatus.READY.equals(status)) {
            return OrderStatus.label(status, deliveryType);
        }
        String fallback = DictCodes.PAY_STATUS.equals(typeCode)
                ? PayStatus.label(status) : OrderStatus.label(status, deliveryType);
        return dictService.label(typeCode, status, fallback);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 写入前按列宽截断：超长在 MySQL 严格模式下会让整条语句失败，等于丢了这条记录。 */
    private static String truncate(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null || trimmed.length() <= MAX_REMARK
                ? trimmed : trimmed.substring(0, MAX_REMARK);
    }

    /**
     * 时间轴一行。中文名与配色随行下发，前端不必再维护一份动作码映射。
     *
     * @param fromStatusLabel 无前值时为空
     * @param toStatusLabel   事件类为空
     * @param fromStatusTag   状态标签配色（字典配置），查不到为 null
     * @param payMethodLabel  收款方式中文名；非收款方式那两条之外为 null
     */
    public record Row(Long id, String logType, String action, String actionLabel,
                      String fromStatus, String fromStatusLabel, String fromStatusTag,
                      String toStatus, String toStatusLabel, String toStatusTag,
                      String remark, String payMethod, String payMethodLabel,
                      Long operatorId, String operatorName, String source, String sourceLabel,
                      LocalDateTime createdAt) {
    }
}

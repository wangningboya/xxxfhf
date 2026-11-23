package com.xxxfhf.meal.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.dict.DictCodes;
import com.xxxfhf.meal.dict.DictService;
import com.xxxfhf.meal.menu.DateParam;
import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.notify.NotifyService;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家端订单处理：接单、拒单、推进状态、取餐核销、收款与退款、取消审批。
 *
 * **所有状态变更都用条件更新**（`WHERE status = 期望的起始值`），受影响行数为 0 说明
 * 已被别的入口改过，直接返回失败。这比「查出来判断再更新」多一次数据库交互，
 * 但能防住「接单的同时用户提交了取消」这类只在并发下出现的脏数据。
 */
@Service
public class OrderAdminService {

    private static final Logger log = LoggerFactory.getLogger(OrderAdminService.class);

    private static final int MAX_BATCH_ACCEPT = 50;

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final UserMapper userMapper;
    private final ServeDateClock clock;
    private final NotifyService notifyService;
    private final OrderStatusLogService statusLogService;
    private final OrderStatusLogMapper statusLogMapper;
    private final DictService dictService;

    public OrderAdminService(OrderMapper orderMapper, OrderService orderService,
                             UserMapper userMapper, ServeDateClock clock,
                             NotifyService notifyService, OrderStatusLogService statusLogService,
                             OrderStatusLogMapper statusLogMapper, DictService dictService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.userMapper = userMapper;
        this.clock = clock;
        this.notifyService = notifyService;
        this.statusLogService = statusLogService;
        this.statusLogMapper = statusLogMapper;
        this.dictService = dictService;
    }

    /* ==================== 查询 ==================== */

    /**
     * 订单列表。
     *
     * 排序刻意做成「份数已释放的置顶 + 待接单优先」：早上打开列表，
     * 最需要先处理的就是那几笔超时未付款、份数已经被别人买走的单；
     * 同一状态内按创建时间倒序，最新下的单排在最上面。
     */
    public IPage<AdminOrderRow> list(String serveDateRaw, String status, String keyword,
                                     Boolean cancelRequested, int page, int pageSize) {
        LocalDate serveDate = DateParam.parse(serveDateRaw, "供餐日期");
        Page<Order> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));

        String kw = keyword == null ? "" : keyword.trim();
        List<Long> matchedUserIds = new ArrayList<>();
        if (!kw.isEmpty()) {
            // 订单表没有用户昵称，先按昵称 / 手机号查出用户 ID 再筛订单
            List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                    .select(User::getId)
                    .and(w -> w.like(User::getNickName, kw).or().like(User::getPhone, kw)));
            if (users.isEmpty()) {
                Page<AdminOrderRow> empty = new Page<>(pager.getCurrent(), pager.getSize(), 0);
                empty.setRecords(List.of());
                return empty;
            }
            users.forEach(u -> matchedUserIds.add(u.getId()));
        }

        IPage<Order> result = orderMapper.selectPage(pager, Wrappers.<Order>lambdaQuery()
                .eq(serveDate != null, Order::getServeDate, serveDate)
                .eq(status != null && !status.isBlank(), Order::getStatus, status)
                .eq(Boolean.TRUE.equals(cancelRequested), Order::getCancelRequested, true)
                .in(!matchedUserIds.isEmpty(), Order::getUserId, matchedUserIds)
                .last("ORDER BY stock_released DESC, "
                        + "FIELD(status, 'pending_accept', 'accepted', 'ready', "
                        + "'completed', 'cancelled', 'rejected'), created_at DESC"));

        Page<AdminOrderRow> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(toRows(result.getRecords()));
        return mapped;
    }

    public AdminOrderRow detail(Long orderId) {
        Order order = orderService.require(orderId);
        return toRows(List.of(order)).get(0);
    }

    /* ==================== 状态流转 ==================== */

    /**
     * 接单（支持批量）。**不再扣份数** —— 份数在下单时就占住了，这里只确认。
     *
     * 逐单处理，单笔失败不影响其它单：早上一批十几单里有一单状态不对，
     * 不该让整批都接不了。
     */
    @Transactional
    public AcceptResult accept(Long operatorId, List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw BizException.param("请选择要接单的订单");
        }
        if (orderIds.size() > MAX_BATCH_ACCEPT) {
            throw BizException.param("一次最多接 " + MAX_BATCH_ACCEPT + " 单");
        }

        List<String> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Long orderId : orderIds) {
            Order order = orderId == null ? null : orderMapper.selectById(orderId);
            if (order == null) {
                skipped.add("订单不存在：" + orderId);
                continue;
            }
            if (Boolean.TRUE.equals(order.getCancelRequested())) {
                skipped.add(order.getOrderNo() + "：用户已申请取消，请先批或驳再处理");
                continue;
            }
            // 业务时间戳与流转记录取同一个 now：两处差几毫秒，排查时会以为是两次操作
            LocalDateTime now = LocalDateTime.now();
            // 允许的起始状态来自转移表，SQL 与校验不再各写一份
            int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, OrderTransition.ACCEPT.target())
                    .set(Order::getAcceptedAt, now)
                    .eq(Order::getId, orderId)
                    .in(Order::getStatus, OrderTransition.ACCEPT.fromStatuses())
                    .eq(Order::getCancelRequested, false));
            if (affected == 0) {
                skipped.add(order.getOrderNo() + "：订单状态已变化");
                continue;
            }
            accepted.add(order.getOrderNo());
            if (Boolean.TRUE.equals(order.getStockReleased())) {
                warnings.add(order.getOrderNo() + "：份数曾因超时未付款被释放，请确认该盒饭是否还有余量");
            }
            statusLogService.record(order.getId(), OrderTransition.ACCEPT.logAction(),
                    order.getStatus(), OrderTransition.ACCEPT.target(), null, now);
            // 通知用户「已接单」。用更新前的对象即可：模板只用到订单号、明细、供餐日这些不变字段
            notifyService.accepted(order);
        }

        log.info("批量接单 操作人={} 成功={} 跳过={}", operatorId, accepted.size(), skipped.size());
        return new AcceptResult(accepted, skipped, warnings);
    }

    /** 拒单：原因必填、释放份数、已收钱转待退款。 */
    @Transactional
    public OrderService.OrderView reject(Long operatorId, Long orderId, String reason) {
        String trimmed = requireReason(reason);
        Order order = orderService.require(orderId);
        OrderTransition.REJECT.require(order.getStatus());

        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, OrderTransition.REJECT.target())
                .set(Order::getRejectReason, trimmed)
                .eq(Order::getId, orderId)
                .in(Order::getStatus, OrderTransition.REJECT.fromStatuses()));
        if (affected == 0) {
            // 上面刚校验过状态，走到这里只可能是并发改动，与「状态本来就不对」要给不同的话
            throw BizException.conflict("订单状态已变化，请刷新后重试");
        }

        // 先记拒单再动份数与收款：时间轴上「已拒单」要排在「转入待退款」之前
        statusLogService.record(orderId, OrderTransition.REJECT.logAction(),
                order.getStatus(), OrderTransition.REJECT.target(), trimmed, LocalDateTime.now());
        orderService.releaseStock(order);
        orderService.moveToRefundingIfPaid(orderId);
        log.info("商家拒单 orderNo={} 操作人={} 原因={}", order.getOrderNo(), operatorId, trimmed);
        Order rejected = orderService.require(orderId);
        // 拒单必须通知到人：用户可能已经转了钱，只靠他自己刷页面发现太晚
        notifyService.rejected(rejected);
        return orderService.toView(rejected);
    }

    /**
     * 推进状态。没有「制作中」：已接单 → 出餐（ready）→ 已完成。
     *
     * 取送方式不改变路径，只改变 ready 那一格的叫法与通知：自取叫「待取餐」要发取餐码，
     * 配送叫「配送中」，不发取餐通知。
     */
    @Transactional
    public OrderService.OrderView advance(Long operatorId, Long orderId, String target) {
        if (target == null || target.isBlank()) {
            throw BizException.param("缺少目标状态");
        }
        Order order = orderService.require(orderId);
        // 命中的转移同时给出目标状态与动作码，不用再按 target 反推该记 ORDER_READY 还是 ORDER_COMPLETE
        OrderTransition transition = OrderTransition.advance(order.getStatus(), target).orElse(null);
        if (transition == null) {
            String next = OrderTransition.nextOf(order.getStatus());
            throw BizException.conflict("订单当前状态（"
                    + OrderStatus.label(order.getStatus(), order.getDeliveryType())
                    + "）不能推进到「" + OrderStatus.label(target, order.getDeliveryType()) + "」"
                    + (next == null ? "" : "，下一步只能是「"
                    + OrderStatus.label(next, order.getDeliveryType()) + "」"));
        }
        // 未到供餐日也放行：商家常要提前把「出餐 → 完成」点一遍串流程。
        // 不拦，只留一条警告 —— 事后翻日志能看出这单是提前操作的。
        if (order.getServeDate() != null && order.getServeDate().isAfter(clock.today())) {
            log.warn("提前推进订单 orderNo={} 供餐日={} 今天={} {} -> {} 操作人={}",
                    order.getOrderNo(), order.getServeDate(), clock.today(),
                    order.getStatus(), target, operatorId);
        }
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, transition.target())
                .eq(Order::getId, orderId)
                .in(Order::getStatus, transition.fromStatuses()));
        if (affected == 0) {
            throw BizException.conflict("订单状态已变化，请刷新后重试");
        }
        log.info("订单推进 orderNo={} {} -> {} 操作人={}",
                order.getOrderNo(), order.getStatus(), transition.target(), operatorId);
        Order advanced = orderService.require(orderId);
        // 出餐与完成分成两个动作码：目标状态不同，商家在时间轴上一眼能看出这单走到哪一步
        statusLogService.record(orderId, transition.logAction(),
                order.getStatus(), transition.target(), null, LocalDateTime.now());
        if (transition == OrderTransition.READY) {
            // 自取单发「可以取餐了」+ 取餐码，配送单发「订单配送中」，模板由
            // NotifyService.ready 按取送方式分流 —— 两种单要说的不是同一句话
            notifyService.ready(advanced);
        }
        return orderService.toView(advanced);
    }

    /**
     * 取餐核销：输 4 位码把订单推到已完成。
     *
     * 只认「出餐（ready）」：待接单还没做、已接单还没出餐，都要先推进到 ready 再核销，
     * 否则等于绕开状态机直接出餐/送达；已完成的不再重复核销。
     * 自取单与配送单同一口径 —— 码是餐盒上的核对凭据，用户看不到不代表商家不能用。
     */
    @Transactional
    public OrderService.OrderView pickupByCode(Long operatorId, String serveDateRaw, String code) {
        LocalDate serveDate = DateParam.parse(serveDateRaw, "供餐日期");
        if (serveDate == null) {
            serveDate = clock.today();
        }
        String trimmed = code == null ? "" : code.trim();
        if (!trimmed.matches("\\d{4}")) {
            throw BizException.param("请输入 4 位取餐码");
        }

        List<Order> hits = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getServeDate, serveDate)
                .eq(Order::getPickupCode, trimmed)
                .last("LIMIT 1"));
        if (hits.isEmpty()) {
            throw BizException.notFound("没有找到取餐码 " + trimmed + " 对应的订单，请核对供餐日");
        }
        Order order = hits.get(0);
        if (!OrderTransition.PICKUP.allows(order.getStatus())) {
            String label = OrderStatus.label(order.getStatus(), order.getDeliveryType());
            if (OrderStatus.ACCEPTED.equals(order.getStatus())) {
                // 已接单还没出餐：连「下一步该点什么」一起给出来，只说不能核销商家会卡在这
                throw BizException.conflict("订单还没出餐（当前「" + label + "」），请先推进到「"
                        + OrderStatus.label(OrderStatus.READY, order.getDeliveryType()) + "」再核销");
            }
            throw BizException.conflict("订单当前状态是「" + label + "」，不能核销");
        }
        // 与 advance 一致：未到供餐日不拦，只留痕（提前核销常出现在串流程时）
        if (serveDate.isAfter(clock.today())) {
            log.warn("提前核销 orderNo={} 供餐日={} 今天={} 操作人={}",
                    order.getOrderNo(), serveDate, clock.today(), operatorId);
        }

        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, OrderTransition.PICKUP.target())
                .eq(Order::getId, order.getId())
                .in(Order::getStatus, OrderTransition.PICKUP.fromStatuses()));
        if (affected == 0) {
            throw BizException.conflict("订单状态已变化，请刷新后重试");
        }
        log.info("取餐核销 orderNo={} code={} 操作人={}", order.getOrderNo(), trimmed, operatorId);
        // 核销的目标态也是「已完成」，但和商家手动点「完成订单」分成两个动作码：
        // 事后要分得清这单是输码核对的，还是直接点了按钮
        statusLogService.record(order.getId(), OrderTransition.PICKUP.logAction(),
                order.getStatus(), OrderTransition.PICKUP.target(), "取餐码 " + trimmed,
                LocalDateTime.now());
        return orderService.toView(orderService.require(order.getId()));
    }

    /* ==================== 联系顾客 ==================== */

    /** 订单结束（完成/取消/拒单）之后仍可调取号码的天数，留出退款与售后沟通的窗口 */
    private static final int CONTACT_WINDOW_DAYS = 7;
    /** 同一笔订单 24 小时内最多调取几次 */
    private static final int CONTACT_MAX_PER_ORDER_24H = 5;
    /** 同一个操作人一天最多调取几次 */
    private static final int CONTACT_MAX_PER_OPERATOR_DAY = 100;

    /**
     * 联系顾客：按需、限次、留痕地把完整号码交给商家拨号。
     *
     * <p>列表里的号码一律是掩码，只有真要打电话时才取一次全号；这次调取会写进订单时间轴，
     * 「谁在什么时候看了哪一单的号码」事后可查。限次防的不是用户，是页面上无意识地
     * 点来点去把号码摊开。
     *
     * <p>返回的明文只在这一次响应的内存里存在，前端不落缓存、不进 data —— 拨完即忘。
     */
    @Transactional
    public String contactPhone(Long operatorId, Long orderId) {
        Order order = orderService.require(orderId);
        LocalDateTime now = LocalDateTime.now();

        LocalDateTime terminalAt = terminalAt(order);
        if (terminalAt != null && terminalAt.isBefore(now.minusDays(CONTACT_WINDOW_DAYS))) {
            throw BizException.conflict("订单结束已超过 " + CONTACT_WINDOW_DAYS + " 天，不能再调取顾客号码");
        }

        long inOrder = statusLogMapper.selectCount(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getAction, OrderLogActions.EVENT_CONTACT_PHONE)
                .eq(OrderStatusLog::getOrderId, orderId)
                .ge(OrderStatusLog::getCreatedAt, now.minusHours(24)));
        if (inOrder >= CONTACT_MAX_PER_ORDER_24H) {
            throw BizException.conflict("这单 24 小时内已调取 " + CONTACT_MAX_PER_ORDER_24H
                    + " 次号码，先看订单备注或稍后再试");
        }

        long today = statusLogMapper.selectCount(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getAction, OrderLogActions.EVENT_CONTACT_PHONE)
                .eq(OrderStatusLog::getOperatorId, operatorId)
                .ge(OrderStatusLog::getCreatedAt, LocalDate.now().atStartOfDay()));
        if (today >= CONTACT_MAX_PER_OPERATOR_DAY) {
            throw BizException.conflict("今天调取号码已达上限 " + CONTACT_MAX_PER_OPERATOR_DAY + " 次");
        }

        String phone = contactNumberOf(order);
        if (phone == null) {
            throw BizException.notFound("这笔订单没有留联系电话，无法拨号");
        }
        statusLogService.record(orderId, OrderLogActions.EVENT_CONTACT_PHONE, null, null, "调取号码拨号", now);
        log.info("调取顾客号码 orderNo={} 操作人={}", order.getOrderNo(), operatorId);
        return phone;
    }

    /** 终态时间：订单主表没存完成时间，只能从时间轴里取最后一个终态 */
    private LocalDateTime terminalAt(Order order) {
        if (!OrderStatus.isFinal(order.getStatus())) {
            return null;
        }
        OrderStatusLog last = statusLogMapper.selectOne(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, order.getId())
                .in(OrderStatusLog::getToStatus,
                        List.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.REJECTED))
                .orderByDesc(OrderStatusLog::getCreatedAt)
                .last("LIMIT 1"));
        return last == null ? null : last.getCreatedAt();
    }

    /**
     * 只认下单时确认的收货电话（订单快照）。
     *
     * <p>详情页展示的号码和「联系顾客」拨出去的号码必须同源，否则商家看到 A 拨到 B，
     * 只会以为系统串单。所以这里刻意<b>不回退</b>账号手机号：自取单没留号就是没留号，
     * 让商家看到「未留电话」，而不是悄悄拨一个用户没在这单里确认过的号码。
     */
    private String contactNumberOf(Order order) {
        AddressSnapshot snapshot = order.getAddressSnapshot();
        if (snapshot == null || snapshot.phone() == null || snapshot.phone().isBlank()) {
            return null;
        }
        return snapshot.phone().trim();
    }

    /* ==================== 收款与退款 ==================== */

    /**
     * 确认收款：未收款 / 用户声称已付款 → 已确认收款。
     *
     * <p><b>收款方式必填</b>（需求 4.10 / N-33）：现金单用户根本不点【我已完成付款】，
     * 若确认时不再选一次，这类单永远没有方式值。前端默认带出用户申报值、可改，
     * 两条记录值不同就是「用户说微信、实际收现金」的差异依据。
     *
     * <p><b>两端都调这个接口</b>：网页后台 {@code OrderList.vue} 与小程序
     * {@code pages/merchant/order-detail} 都走 {@code POST /admin/order/payment}，
     * 所以必填校验落地时两端弹窗必须同步上线，否则小程序端确认收款会直接 400。
     *
     * <p>合法值以字典 {@code pay_method} 为准：商家在字典里新增的方式也能直接收，
     * 代码里不再写死三个常量。
     */
    @Transactional
    public OrderService.OrderView confirmPayment(Long operatorId, Long orderId,
                                                 String payMethodRaw) {
        Order order = orderService.require(orderId);
        String payMethod = dictService.requireValue(DictCodes.PAY_METHOD, "收款方式",
                payMethodRaw, "确认收款");
        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.PAID)
                .set(Order::getPaidAt, now)
                .set(Order::getPayMethod, payMethod)
                .eq(Order::getId, orderId)
                .in(Order::getPayStatus, List.of(PayStatus.UNPAID, PayStatus.CLAIMED)));
        if (affected == 0) {
            throw BizException.conflict("当前收款状态是「" + PayStatus.label(order.getPayStatus())
                    + "」，不能确认收款");
        }
        log.info("确认收款 orderNo={} 操作人={} 方式={}",
                order.getOrderNo(), operatorId, payMethod);
        // from 取更新前的收款状态：未收款直接确认、用户声称后确认，时间轴上能分清这两种来路
        statusLogService.record(orderId, OrderLogActions.PAY_CONFIRM,
                order.getPayStatus(), PayStatus.PAID, null, payMethod, now);
        return orderService.toView(orderService.require(orderId));
    }

    /**
     * 回退为未收款。
     *
     * 用在「用户说付了但商家查不到」的场景：状态与事实不符时必须能改回去，
     * 否则商家只能一直挂着一笔不存在的钱。
     */
    @Transactional
    public OrderService.OrderView revertPayment(Long operatorId, Long orderId) {
        Order order = orderService.require(orderId);
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.UNPAID)
                .set(Order::getPaidAt, null)
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, PayStatus.PAID));
        if (affected == 0) {
            throw BizException.conflict("只有「已确认收款」可以回退，当前状态是「"
                    + PayStatus.label(order.getPayStatus()) + "」");
        }
        log.info("收款回退 orderNo={} 操作人={}", order.getOrderNo(), operatorId);
        statusLogService.record(orderId, OrderLogActions.PAY_REVERT,
                PayStatus.PAID, PayStatus.UNPAID, null, LocalDateTime.now());
        return orderService.toView(orderService.require(orderId));
    }

    /**
     * 标记退款完成：待退款 → 已退款。
     *
     * 条件更新带 `pay_status='refunding'`，重复点第二次会失败 ——
     * 这就是「防重复退」的落地方式，同时留下操作人与时间。
     */
    @Transactional
    public OrderService.OrderView refund(Long operatorId, Long orderId) {
        Order order = orderService.require(orderId);
        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.REFUNDED)
                .set(Order::getRefundedAt, now)
                .set(Order::getRefundOperator, operatorId)
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, PayStatus.REFUNDING));
        if (affected == 0) {
            throw BizException.conflict("只有「待退款」可以标记退款，当前状态是「"
                    + PayStatus.label(order.getPayStatus()) + "」");
        }
        log.info("标记退款完成 orderNo={} 操作人={}", order.getOrderNo(), operatorId);
        statusLogService.record(orderId, OrderLogActions.PAY_REFUND,
                PayStatus.REFUNDING, PayStatus.REFUNDED, null, now);
        return orderService.toView(orderService.require(orderId));
    }

    /* ==================== 取消申请审批 ==================== */

    /**
     * 审批用户的取消申请。
     *
     * 同意：订单转已取消 + 释放份数 + 已收钱转待退款；
     * 驳回：只清掉申请标记，订单回到正常流转（接单按钮随之恢复），备注原因回给用户。
     */
    @Transactional
    public OrderService.OrderView handleCancelRequest(Long operatorId, Long orderId,
                                                      boolean approve, String remark) {
        return doHandleCancelRequest(operatorId, orderId, approve, remark,
                approve ? CancelRequest.APPROVED : CancelRequest.REJECTED);
    }

    /**
     * 超时自动同意（N-14）。
     *
     * 与商家手动同意走同一条逻辑，只有两处不同：结果记 `auto_approved`、
     * 操作人留空表示系统。**不另写一套状态流转**，否则两条路径迟早会分叉。
     */
    @Transactional
    public OrderService.OrderView autoApproveCancelRequest(Long orderId, int timeoutHours) {
        return doHandleCancelRequest(null, orderId, true,
                "超过 " + timeoutHours + " 小时未处理，系统自动同意", CancelRequest.AUTO_APPROVED);
    }

    private OrderService.OrderView doHandleCancelRequest(Long operatorId, Long orderId,
                                                         boolean approve, String remark,
                                                         String result) {
        Order order = orderService.require(orderId);
        if (!Boolean.TRUE.equals(order.getCancelRequested())) {
            throw BizException.conflict("该订单没有待处理的取消申请");
        }
        String trimmedRemark = remark == null || remark.isBlank() ? null : remark.trim();
        LocalDateTime now = LocalDateTime.now();
        CancelRequest origin = order.getCancelRequest();
        CancelRequest handled = new CancelRequest(
                origin == null ? null : origin.reason(),
                origin == null ? null : origin.appliedAt(),
                now,
                result,
                operatorId,
                trimmedRemark);

        // 定时任务没有登录态，操作人与来源必须显式给 —— 事后看到「系统同意的」，
        // 才知道当时商家根本没处理，这跟「商家点了同意」是两回事
        Long actorId = operatorId == null ? AuditHelper.SYSTEM : operatorId;
        String source = operatorId == null ? AuditHelper.SOURCE_TASK : AuditHelper.source();

        if (approve) {
            // 终态单不该再有可同意的申请（申请与拒单并发时会留下这种脏数据）：
            // 驳回分支不能加这条校验，否则那种单的申请永远清不掉，卡在待处理列表里
            OrderTransition.CANCEL_APPROVED.require(order.getStatus(), order.getDeliveryType());
            int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, OrderTransition.CANCEL_APPROVED.target())
                    .set(Order::getCancelRequested, false)
                    .set(Order::getCancelRequest, handled)
                    .set(Order::getCancelRequestedAt, null)
                    .eq(Order::getId, orderId)
                    .in(Order::getStatus, OrderTransition.CANCEL_APPROVED.fromStatuses())
                    .eq(Order::getCancelRequested, true));
            if (affected == 0) {
                throw BizException.conflict("申请状态已变化，请刷新后重试");
            }
            // 同意取消与超时自动同意共用一个动作码，靠来源（admin / task）区分：
            // 同一件事的两种触发方式，拆两个码只会让前端文案维护两份
            statusLogService.record(orderId, OrderTransition.CANCEL_APPROVED.logAction(),
                    order.getStatus(), OrderTransition.CANCEL_APPROVED.target(),
                    trimmedRemark, actorId, source, now);
            orderService.releaseStock(order);
            orderService.moveToRefundingIfPaid(orderId);
        } else {
            int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getCancelRequested, false)
                    .set(Order::getCancelRequest, handled)
                    .set(Order::getCancelRequestedAt, null)
                    .eq(Order::getId, orderId)
                    .eq(Order::getCancelRequested, true));
            if (affected == 0) {
                throw BizException.conflict("申请状态已变化，请刷新后重试");
            }
            // 驳回不改订单状态，属事件类；不记的话时间轴上会出现「申请取消」却查不到结果
            statusLogService.record(orderId, OrderLogActions.EVENT_CANCEL_DENY,
                    null, null, trimmedRemark, actorId, source, now);
        }

        log.info("取消申请审批 orderNo={} 结果={} 操作人={} 备注={}",
                order.getOrderNo(), result, operatorId == null ? "系统" : operatorId,
                trimmedRemark == null ? "" : trimmedRemark);
        Order handledOrder = orderService.require(orderId);
        if (approve) {
            // 同意了就等于这单没了，商家要及时知道，别再排进备菜
            notifyService.cancelled(handledOrder);
        }
        return orderService.toView(handledOrder);
    }

    /* ==================== 内部 ==================== */

    /** 批量补用户昵称与手机号，避免逐行查用户（N+1）。 */
    private List<AdminOrderRow> toRows(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = orders.stream().map(Order::getUserId).distinct().toList();
        Map<Long, User> users = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            users.put(user.getId(), user);
        }
        List<AdminOrderRow> rows = new ArrayList<>(orders.size());
        for (Order order : orders) {
            User user = users.get(order.getUserId());
            AddressSnapshot snapshot = order.getAddressSnapshot();
            rows.add(new AdminOrderRow(orderService.toView(order),
                    user == null ? null : user.getNickName(),
                    // 账号手机号只是「这单是谁订的」的辅助信息，订单列表按对外口径给前 3 后 2
                    user == null ? null : MaskUtil.phoneShort(user.getPhone()),
                    // 本单联系电话：取原始快照再掩码，不能用 toView 里已掩过的 address（会二次打码）
                    snapshot == null ? null : MaskUtil.phoneShort(snapshot.phone())));
        }
        return rows;
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw BizException.param("拒单必须填写原因，用户端会直接展示");
        }
        if (trimmed.length() > 200) {
            throw BizException.param("拒单原因最多 200 字");
        }
        return trimmed;
    }

    /**
     * 商家端订单行：订单本体 + 下单人信息 + 本单联系电话。
     *
     * 昵称在这张表里没有冗余，列表必须能看出「这是谁订的」，
     * 否则中午接单只能靠订单号猜人。
     *
     * <p>`contactPhone` 是这单下单时确认的收货电话（掩码），与「联系顾客」拨出的号码同源；
     * `userPhone` 是账号手机号，只用来认人，不用于拨号。
     */
    public record AdminOrderRow(OrderService.OrderView order, String userNickName, String userPhone,
                                String contactPhone) {
    }

    /**
     * 批量接单结果。
     *
     * @param accepted 成功的订单号
     * @param skipped  跳过的原因（某一单状态不对不该拖垮整批）
     * @param warnings 成功但有风险提示的单（份数曾被超时释放）
     */
    public record AcceptResult(List<String> accepted, List<String> skipped, List<String> warnings) {
    }
}

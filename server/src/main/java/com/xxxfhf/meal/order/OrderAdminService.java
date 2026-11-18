package com.xxxfhf.meal.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.common.BizException;
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
    private final DictService dictService;

    public OrderAdminService(OrderMapper orderMapper, OrderService orderService,
                             UserMapper userMapper, ServeDateClock clock,
                             NotifyService notifyService, OrderStatusLogService statusLogService,
                             DictService dictService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.userMapper = userMapper;
        this.clock = clock;
        this.notifyService = notifyService;
        this.statusLogService = statusLogService;
        this.dictService = dictService;
    }

    /* ==================== 查询 ==================== */

    /**
     * 订单列表。
     *
     * 排序刻意做成「份数已释放的置顶 + 待接单优先」：早上打开列表，
     * 最需要先处理的就是那几笔超时未付款、份数已经被别人买走的单。
     */
    public IPage<AdminOrderRow> list(String serveDateRaw, String status, String keyword,
                                     Boolean cancelRequested, int page, int pageSize) {
        LocalDate serveDate = DateParam.parse(serveDateRaw, "供餐日期");
        Page<Order> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));

        String kw = keyword == null ? "" : keyword.trim();
        List<String> matchedUserIds = new ArrayList<>();
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
                        + "'completed', 'cancelled', 'rejected'), created_at ASC"));

        Page<AdminOrderRow> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(toRows(result.getRecords()));
        return mapped;
    }

    public AdminOrderRow detail(String orderId) {
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
    public AcceptResult accept(String operatorId, List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw BizException.param("请选择要接单的订单");
        }
        if (orderIds.size() > MAX_BATCH_ACCEPT) {
            throw BizException.param("一次最多接 " + MAX_BATCH_ACCEPT + " 单");
        }

        List<String> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String orderId : orderIds) {
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
            int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, OrderStatus.ACCEPTED)
                    .set(Order::getAcceptedAt, now)
                    .eq(Order::getId, orderId)
                    .eq(Order::getStatus, OrderStatus.PENDING_ACCEPT)
                    .eq(Order::getCancelRequested, false));
            if (affected == 0) {
                skipped.add(order.getOrderNo() + "：订单状态已变化");
                continue;
            }
            accepted.add(order.getOrderNo());
            if (Boolean.TRUE.equals(order.getStockReleased())) {
                warnings.add(order.getOrderNo() + "：份数曾因超时未付款被释放，请确认该盒饭是否还有余量");
            }
            statusLogService.record(order.getId(), OrderLogActions.ORDER_ACCEPT,
                    OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED, null, now);
            // 通知用户「已接单」。用更新前的对象即可：模板只用到订单号、明细、供餐日这些不变字段
            notifyService.accepted(order);
        }

        log.info("批量接单 操作人={} 成功={} 跳过={}", operatorId, accepted.size(), skipped.size());
        return new AcceptResult(accepted, skipped, warnings);
    }

    /** 拒单：原因必填、释放份数、已收钱转待退款。 */
    @Transactional
    public OrderService.OrderView reject(String operatorId, String orderId, String reason) {
        String trimmed = requireReason(reason);
        Order order = orderService.require(orderId);

        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, OrderStatus.REJECTED)
                .set(Order::getRejectReason, trimmed)
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PENDING_ACCEPT));
        if (affected == 0) {
            throw BizException.conflict("只有待接单的订单可以拒单，当前状态是「"
                    + OrderStatus.label(order.getStatus()) + "」");
        }

        // 先记拒单再动份数与收款：时间轴上「已拒单」要排在「转入待退款」之前
        statusLogService.record(orderId, OrderLogActions.ORDER_REJECT,
                OrderStatus.PENDING_ACCEPT, OrderStatus.REJECTED, trimmed, LocalDateTime.now());
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
    public OrderService.OrderView advance(String operatorId, String orderId, String target) {
        if (target == null || target.isBlank()) {
            throw BizException.param("缺少目标状态");
        }
        Order order = orderService.require(orderId);
        if (!OrderStatus.canAdvance(order.getStatus(), target)) {
            String next = OrderStatus.nextOf(order.getStatus());
            throw BizException.conflict("订单当前状态（"
                    + OrderStatus.label(order.getStatus(), order.getDeliveryType())
                    + "）不能推进到「" + OrderStatus.label(target, order.getDeliveryType()) + "」"
                    + (next == null ? "" : "，下一步只能是「"
                    + OrderStatus.label(next, order.getDeliveryType()) + "」"));
        }
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, target)
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, order.getStatus()));
        if (affected == 0) {
            throw BizException.conflict("订单状态已变化，请刷新后重试");
        }
        log.info("订单推进 orderNo={} {} -> {} 操作人={}",
                order.getOrderNo(), order.getStatus(), target, operatorId);
        Order advanced = orderService.require(orderId);
        // 出餐与完成分成两个动作码：目标状态不同，商家在时间轴上一眼能看出这单走到哪一步
        statusLogService.record(orderId,
                OrderStatus.READY.equals(target) ? OrderLogActions.ORDER_READY : OrderLogActions.ORDER_COMPLETE,
                order.getStatus(), target, null, LocalDateTime.now());
        if (OrderStatus.READY.equals(target)
                && !OrderStatus.TYPE_DELIVERY.equals(advanced.getDeliveryType())) {
            // 只有自取单发「可以取餐了」+ 取餐码。配送单进的是同一个 ready，
            // 但用户在等送货上门，硬套取餐模板等于告诉他「自己去拿」，是错的提醒 —— 静默不发。
            notifyService.ready(advanced);
        }
        return orderService.toView(advanced);
    }

    /**
     * 取餐核销：输 4 位码把订单推到已完成。
     *
     * 只认「已接单 / 出餐」：待接单还没做，直接核销等于凭空出餐；已完成的不再重复核销。
     * 配送单也走这里 —— 码是餐盒上的核对凭据，用户看不到不代表商家不能用。
     */
    @Transactional
    public OrderService.OrderView pickupByCode(String operatorId, String serveDateRaw, String code) {
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
        if (!OrderStatus.canPickup(order.getStatus())) {
            throw BizException.conflict("订单当前状态是「"
                    + OrderStatus.label(order.getStatus(), order.getDeliveryType()) + "」，不能核销");
        }

        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, OrderStatus.COMPLETED)
                .eq(Order::getId, order.getId())
                .in(Order::getStatus, List.of(OrderStatus.ACCEPTED, OrderStatus.READY)));
        if (affected == 0) {
            throw BizException.conflict("订单状态已变化，请刷新后重试");
        }
        log.info("取餐核销 orderNo={} code={} 操作人={}", order.getOrderNo(), trimmed, operatorId);
        // 核销的目标态也是「已完成」，但和商家手动点「完成订单」分成两个动作码：
        // 事后要分得清这单是输码核对的，还是直接点了按钮
        statusLogService.record(order.getId(), OrderLogActions.ORDER_PICKUP,
                order.getStatus(), OrderStatus.COMPLETED, "取餐码 " + trimmed, LocalDateTime.now());
        return orderService.toView(orderService.require(order.getId()));
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
    public OrderService.OrderView confirmPayment(String operatorId, String orderId,
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
    public OrderService.OrderView revertPayment(String operatorId, String orderId) {
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
    public OrderService.OrderView refund(String operatorId, String orderId) {
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
    public OrderService.OrderView handleCancelRequest(String operatorId, String orderId,
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
    public OrderService.OrderView autoApproveCancelRequest(String orderId, int timeoutHours) {
        return doHandleCancelRequest(null, orderId, true,
                "超过 " + timeoutHours + " 小时未处理，系统自动同意", CancelRequest.AUTO_APPROVED);
    }

    private OrderService.OrderView doHandleCancelRequest(String operatorId, String orderId,
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
        String actorId = operatorId == null ? AuditHelper.SYSTEM : operatorId;
        String source = operatorId == null ? AuditHelper.SOURCE_TASK : AuditHelper.source();

        if (approve) {
            int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, OrderStatus.CANCELLED)
                    .set(Order::getCancelRequested, false)
                    .set(Order::getCancelRequest, handled)
                    .set(Order::getCancelRequestedAt, null)
                    .eq(Order::getId, orderId)
                    .eq(Order::getCancelRequested, true));
            if (affected == 0) {
                throw BizException.conflict("申请状态已变化，请刷新后重试");
            }
            // 同意取消与超时自动同意共用一个动作码，靠来源（admin / task）区分：
            // 同一件事的两种触发方式，拆两个码只会让前端文案维护两份
            statusLogService.record(orderId, OrderLogActions.ORDER_CANCEL,
                    order.getStatus(), OrderStatus.CANCELLED, trimmedRemark, actorId, source, now);
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
        List<String> userIds = orders.stream().map(Order::getUserId).distinct().toList();
        Map<String, User> users = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            users.put(user.getId(), user);
        }
        List<AdminOrderRow> rows = new ArrayList<>(orders.size());
        for (Order order : orders) {
            User user = users.get(order.getUserId());
            rows.add(new AdminOrderRow(orderService.toView(order),
                    user == null ? null : user.getNickName(),
                    user == null ? null : user.getPhone()));
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
     * 商家端订单行：订单本体 + 下单人信息。
     *
     * 昵称在这张表里没有冗余，列表必须能看出「这是谁订的」，
     * 否则中午接单只能靠订单号猜人。
     */
    public record AdminOrderRow(OrderService.OrderView order, String userNickName, String userPhone) {
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

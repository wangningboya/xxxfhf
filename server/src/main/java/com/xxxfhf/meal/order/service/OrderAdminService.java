package com.xxxfhf.meal.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.config.AppProperties;
import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.dict.domain.DictCodes;
import com.xxxfhf.meal.dict.service.DictService;
import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.menu.domain.DateParam;
import com.xxxfhf.meal.notify.service.NotifyService;
import com.xxxfhf.meal.order.domain.AddressSnapshot;
import com.xxxfhf.meal.order.domain.CancelRequest;
import com.xxxfhf.meal.order.domain.Order;
import com.xxxfhf.meal.order.domain.OrderLogActions;
import com.xxxfhf.meal.order.domain.OrderStatus;
import com.xxxfhf.meal.order.domain.OrderStatusLog;
import com.xxxfhf.meal.order.domain.OrderTransition;
import com.xxxfhf.meal.order.domain.PayStatus;
import com.xxxfhf.meal.order.mapper.OrderMapper;
import com.xxxfhf.meal.order.mapper.OrderStatusLogMapper;
import com.xxxfhf.meal.print.service.PrintService;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
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

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final UserMapper userMapper;
    private final ServeDateClock clock;
    private final NotifyService notifyService;
    private final OrderStatusLogService statusLogService;
    private final OrderStatusLogMapper statusLogMapper;
    private final DictService dictService;
    private final PrintService printService;
    /** 批量接单 / 拒单上限走后台配置，调取号码的限次与窗口走环境变量 */
    private final ConfigService configService;
    private final AppProperties appProperties;

    public OrderAdminService(OrderMapper orderMapper, OrderService orderService,
                             UserMapper userMapper, ServeDateClock clock,
                             NotifyService notifyService, OrderStatusLogService statusLogService,
                             OrderStatusLogMapper statusLogMapper, DictService dictService,
                             PrintService printService, ConfigService configService,
                             AppProperties appProperties) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.userMapper = userMapper;
        this.clock = clock;
        this.notifyService = notifyService;
        this.statusLogService = statusLogService;
        this.statusLogMapper = statusLogMapper;
        this.dictService = dictService;
        this.printService = printService;
        this.configService = configService;
        this.appProperties = appProperties;
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
        return list(serveDateRaw, status, keyword, cancelRequested, null, page, pageSize);
    }

    /**
     * @param payStatus 收款状态（v1.53）：工作台「待核对收款」「待退款」两个待办点进来时
     *                  就按这个筛。传空为不限 —— 与 {@code cancelRequested} 一样，
     *                  用工单事实筛比让商家自己在列表里找可靠
     */
    public IPage<AdminOrderRow> list(String serveDateRaw, String status, String keyword,
                                     Boolean cancelRequested, String payStatus,
                                     int page, int pageSize) {
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
                .eq(payStatus != null && !payStatus.isBlank(), Order::getPayStatus, payStatus)
                .eq(Boolean.TRUE.equals(cancelRequested), Order::getCancelRequested, true)
                .in(!matchedUserIds.isEmpty(), Order::getUserId, matchedUserIds)
                .last("ORDER BY stock_released DESC, "
                        + "FIELD(status, 'pending_accept', 'accepted', 'ready', "
                        + "'completed', 'cancelled', 'rejected'), created_at DESC"));

        Page<AdminOrderRow> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(toRows(result.getRecords()));
        return mapped;
    }

    /**
     * 按订单号取 ID。扫标签上的小程序码落地时用 —— 扫码进来手里只有订单号。
     */
    public Long idByOrderNo(String orderNo) {
        return orderService.byOrderNo(orderNo).getId();
    }

    public AdminOrderRow detail(Long orderId) {
        Order order = orderService.require(orderId);
        AdminOrderRow row = toRows(List.of(order)).get(0);
        // 打印记录只给详情：列表一次 20 单，把日志表扫一遍不值当（需求 6.2）
        return new AdminOrderRow(row.order(), row.userNickName(), row.userPhone(),
                row.contactPhone(), row.print(), printService.recordsOf(orderId));
    }

    /**
     * 催收清单（v3.50 / 需求 4.21 / `F-F07`）：**餐已经给了、钱还没到**的单。
     *
     * <p>口径 {@code status ∈ {ready, completed}} 且 {@code pay_status ∈ {unpaid, claimed}}，
     * **不限供餐日** —— 要捞的正是往期漏收的单，带上默认的「明天」等于先把要找的单排除掉。
     *
     * <p>含「出餐（配送中）」而不等「已完成」：现金单是在门口、在取餐那一刻收的钱，
     * 等他点完「已送达」再催，等于把整天的现金单全堆到晚上。
     *
     * <p>按供餐日升序：欠得最久的排最前。同一天按订单号，方便照着顺序一单一单核。
     */
    public IPage<AdminOrderRow> collect(int page, int pageSize) {
        Page<Order> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        IPage<Order> result = orderMapper.selectPage(pager, collectQuery());

        Page<AdminOrderRow> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(toRows(result.getRecords()));
        return mapped;
    }

    /** 催收清单的筛选与排序，分页查询与导出共用一份，避免两处口径写出差别。 */
    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order> collectQuery() {
        return Wrappers.<Order>lambdaQuery()
                .in(Order::getStatus, List.of(OrderStatus.READY, OrderStatus.COMPLETED))
                .in(Order::getPayStatus, List.of(PayStatus.UNPAID, PayStatus.CLAIMED))
                .orderByAsc(Order::getServeDate)
                .orderByAsc(Order::getOrderNo);
    }

    /**
     * 导出催收清单（v3.50）。
     *
     * <p>不分页：单日几百单的量级，一次写完最省事，也不会出现「导出的比页面少」。
     * 手机号按掩码导出 —— 这张表常在店里传阅，完整号码没什么用处。
     */
    public byte[] exportCollect() {
        List<Order> orders = orderMapper.selectList(collectQuery());
        // toRows 会批量补昵称，且**顺序与入参一致**，按下标配对即可，不必再按 ID 回查
        List<AdminOrderRow> mapped = toRows(orders);
        List<CollectExcelRow> rows = new ArrayList<>(orders.size());
        LocalDate today = clock.today();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            rows.add(new CollectExcelRow(
                    order.getOrderNo(),
                    order.getServeDate() == null ? "" : order.getServeDate().toString(),
                    mapped.get(i).userNickName(),
                    mapped.get(i).contactPhone(),
                    yuan(order.getTotalAmount()),
                    "delivery".equals(order.getDeliveryType()) ? "配送" : "自取",
                    PayStatus.label(order.getPayStatus()),
                    overdueDays(order.getServeDate(), today),
                    order.getRemark() == null ? "" : order.getRemark()));
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (com.alibaba.excel.ExcelWriter writer = com.alibaba.excel.EasyExcel.write(out).build()) {
            com.alibaba.excel.write.metadata.WriteSheet sheet = com.alibaba.excel.EasyExcel
                    .writerSheet(0, "待收款").head(CollectExcelRow.class).build();
            writer.write(rows, sheet);
        }
        return out.toByteArray();
    }

    /** 逾期天数：供餐日到今天是第几天。当天出的餐算 0 天，不算逾期。 */
    private static int overdueDays(LocalDate serveDate, LocalDate today) {
        if (serveDate == null) {
            return 0;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(serveDate, today);
        return (int) Math.max(0, days);
    }

    private static String yuan(Integer fen) {
        return fen == null ? "0.00" : String.format("%.2f", fen / 100.0);
    }

    /** 催收清单导出的一行。列顺序即表头顺序，采购 / 收银照着念就行 */
    public static class CollectExcelRow {
        @com.alibaba.excel.annotation.ExcelProperty("订单号")
        private String orderNo;
        @com.alibaba.excel.annotation.ExcelProperty("供餐日")
        private String serveDate;
        @com.alibaba.excel.annotation.ExcelProperty("顾客")
        private String nickName;
        @com.alibaba.excel.annotation.ExcelProperty("联系电话")
        private String phone;
        @com.alibaba.excel.annotation.ExcelProperty("金额（元）")
        private String amount;
        @com.alibaba.excel.annotation.ExcelProperty("取送")
        private String deliveryType;
        @com.alibaba.excel.annotation.ExcelProperty("收款状态")
        private String payStatus;
        @com.alibaba.excel.annotation.ExcelProperty("逾期天数")
        private int overdueDays;
        @com.alibaba.excel.annotation.ExcelProperty("整单备注")
        private String remark;

        public CollectExcelRow() {
        }

        public CollectExcelRow(String orderNo, String serveDate, String nickName, String phone,
                               String amount, String deliveryType, String payStatus,
                               int overdueDays, String remark) {
            this.orderNo = orderNo;
            this.serveDate = serveDate;
            this.nickName = nickName;
            this.phone = phone;
            this.amount = amount;
            this.deliveryType = deliveryType;
            this.payStatus = payStatus;
            this.overdueDays = overdueDays;
            this.remark = remark;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public String getServeDate() {
            return serveDate;
        }

        public String getNickName() {
            return nickName;
        }

        public String getPhone() {
            return phone;
        }

        public String getAmount() {
            return amount;
        }

        public String getDeliveryType() {
            return deliveryType;
        }

        public String getPayStatus() {
            return payStatus;
        }

        public int getOverdueDays() {
            return overdueDays;
        }

        public String getRemark() {
            return remark;
        }
    }

    /* ==================== 状态流转 ==================== */

    /**
     * 一次批量接/拒的上限，商家可在后台调（默认 20）。
     *
     * 两个入口共用同一个值：接单和拒单的界面是同一张列表、同一套勾选，
     * 配成两个数只会让商家的「全选」有时成功有时报错。
     */
    private int batchLimit() {
        return configService.getPositiveInt(
                ConfigKey.ORDER_BATCH_LIMIT, ConfigKey.ORDER_BATCH_LIMIT_DEFAULT);
    }

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
        int maxBatch = batchLimit();
        if (orderIds.size() > maxBatch) {
            throw BizException.param("一次最多接 " + maxBatch + " 单");
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
     * 批量拒单（v1.53 / 需求 4.11 / F-J11）：整批共用一条原因。
     *
     * 为什么共用一条：批量拒的典型场景是「明天停业」「主菜缺料」，整批本来就是同一个理由；
     * 逐单填一遍等于把最痛的那一步留着。个别单要不同理由，单独拒即可。
     *
     * 逐单走 {@link #reject} 同一条路径（释放份数 → 转待退款 → 通知 → 记流转），
     * 不另写一套 —— 两条路径迟早会分叉，而分叉的那一次就是份数没释放的那一次。
     * 状态已变 / 不存在的单收进 {@code skipped}，不影响其余单。
     */
    @Transactional
    public RejectResult rejectBatch(Long operatorId, List<Long> orderIds, String reason) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw BizException.param("请选择要拒单的订单");
        }
        int maxBatch = batchLimit();
        if (orderIds.size() > maxBatch) {
            throw BizException.param("一次最多拒 " + maxBatch + " 单");
        }
        // 原因先校验：整批共用，没有「这一单不缺原因、那一单缺」的说法
        String trimmed = requireReason(reason);

        List<String> rejected = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Long orderId : orderIds) {
            Order order = orderId == null ? null : orderMapper.selectById(orderId);
            String orderNo = order == null ? String.valueOf(orderId) : order.getOrderNo();
            try {
                reject(operatorId, orderId, trimmed);
                rejected.add(orderNo);
            } catch (BizException e) {
                // 单笔失败不牵连整批：早上一批十几单里有一单状态不对，不该让整批都拒不了
                skipped.add(orderNo + "：" + e.getMessage());
            }
        }

        log.info("批量拒单 操作人={} 成功={} 跳过={} 原因={}",
                operatorId, rejected.size(), skipped.size(), trimmed);
        return new RejectResult(rejected, skipped);
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
        // 申请中禁用【出餐】：申请只产生于「已接单 + 已过截单」，此时若一边审批取消
        // 一边把餐做出去，商家就白做一份（需求 4.9 取消申请中）
        if (transition == OrderTransition.READY && Boolean.TRUE.equals(order.getCancelRequested())) {
            throw BizException.conflict("用户已申请取消，请先同意或驳回，再推进出餐");
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
        } else if (transition == OrderTransition.COMPLETE) {
            // 配送单在此刻才算「已送达」、自取单算「已取餐」。只落站内消息：
            // 用户侧 3 个订阅模板额度已被接单 / 拒单 / 出餐占满，见 NotifyType.COMPLETED
            notifyService.completed(advanced);
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
        Order finished = orderService.require(order.getId());
        // 核销是自取单的「完成」入口，与手动点「完成订单」走同一条站内消息（只站内、不推送）
        notifyService.completed(finished);
        return orderService.toView(finished);
    }

    /* ==================== 联系顾客 ==================== */

    /**
     * 调取号码的三道限制**全部走环境变量**（APP_CONTACT_PHONE_*），默认 7 天窗口 / 同单 24h 内 5 次 /
     * 每人每天 100 次。不给后台可调：这是隐私合规里的「按需、限次、留痕」，商家自己放宽等于自证违规。
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
        int windowDays = appProperties.getContactPhone().getWindowDays();
        int maxPerOrder = appProperties.getContactPhone().getMaxPerOrder24h();
        int maxPerOperatorDay = appProperties.getContactPhone().getMaxPerOperatorDay();

        LocalDateTime terminalAt = terminalAt(order);
        if (terminalAt != null && terminalAt.isBefore(now.minusDays(windowDays))) {
            throw BizException.conflict("订单结束已超过 " + windowDays + " 天，不能再调取顾客号码");
        }

        long inOrder = statusLogMapper.selectCount(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getAction, OrderLogActions.EVENT_CONTACT_PHONE)
                .eq(OrderStatusLog::getOrderId, orderId)
                .ge(OrderStatusLog::getCreatedAt, now.minusHours(24)));
        if (inOrder >= maxPerOrder) {
            throw BizException.conflict("这单 24 小时内已调取 " + maxPerOrder
                    + " 次号码，先看订单备注或稍后再试");
        }

        long today = statusLogMapper.selectCount(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getAction, OrderLogActions.EVENT_CONTACT_PHONE)
                .eq(OrderStatusLog::getOperatorId, operatorId)
                .ge(OrderStatusLog::getCreatedAt, LocalDate.now().atStartOfDay()));
        if (today >= maxPerOperatorDay) {
            throw BizException.conflict("今天调取号码已达上限 " + maxPerOperatorDay + " 次");
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
     *
     * <p><b>v3.50 起必须填原因</b>（≤200 字），原因写进时间轴并原样进用户站内消息：
     * 用户端只会看到「未收款」，不告诉他为什么，等于让他再来问一遍。
     *
     * @param reason 回退原因，必填
     */
    @Transactional
    public OrderService.OrderView revertPayment(Long operatorId, Long orderId, String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw BizException.param("回退收款必须填写原因，用户端会直接看到");
        }
        if (trimmed.length() > 200) {
            throw BizException.param("回退原因最多 200 字");
        }
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
        log.info("收款回退 orderNo={} 操作人={} 原因={}", order.getOrderNo(), operatorId, trimmed);
        statusLogService.record(orderId, OrderLogActions.PAY_REVERT,
                PayStatus.PAID, PayStatus.UNPAID, trimmed, LocalDateTime.now());
        // 只改状态不说话，是这类纠纷里最糟的一步：用户以为付了、系统显示未收，却没人解释
        Order reverted = orderService.require(orderId);
        notifyService.payReverted(reverted, trimmed);
        return orderService.toView(reverted);
    }

    /**
     * 标记退款完成：待退款 → 已退款。
     *
     * 条件更新带 `pay_status='refunding'`，重复点第二次会失败 ——
     * 这就是「防重复退」的落地方式，同时留下操作人与时间。
     *
     * <p><b>为什么必须记金额（v1.53 / 需求 4.10 / `F-F06`）</b>：线下退款天然会有部分退
     * （少送一份、扣掉已经发生的配送费）。只标「已退款」而不记金额，账面上「这单退了」
     * 和「退了 12 块」是两回事，月底对不上账时没有任何依据。
     *
     * @param refundAmount 实际退款金额（分）。为 null 时按订单合计（兼容没带金额的旧版小程序）；
     *                     须 &gt;0 且 ≤ 订单合计 —— 退得比收得多不是「大方」，是错账
     * @param remark       退款备注（选填，≤200）：退到哪个账户、为什么少退
     */
    @Transactional
    public OrderService.OrderView refund(Long operatorId, Long orderId,
                                         Integer refundAmount, String remark) {
        Order order = orderService.require(orderId);
        int total = order.getTotalAmount() == null ? 0 : order.getTotalAmount();
        int amount = refundAmount == null ? total : refundAmount;
        if (amount <= 0) {
            throw BizException.param("退款金额必须大于 0");
        }
        if (total > 0 && amount > total) {
            throw BizException.param("退款金额不能超过订单合计（" + total + " 分）");
        }
        String trimmedRemark = remark == null || remark.isBlank() ? null : remark.trim();
        if (trimmedRemark != null && trimmedRemark.length() > 200) {
            throw BizException.param("退款备注最多 200 字");
        }

        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.REFUNDED)
                .set(Order::getRefundedAt, now)
                .set(Order::getRefundOperator, operatorId)
                .set(Order::getRefundAmount, amount)
                .set(Order::getRefundRemark, trimmedRemark)
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, PayStatus.REFUNDING));
        if (affected == 0) {
            throw BizException.conflict("只有「待退款」可以标记退款，当前状态是「"
                    + PayStatus.label(order.getPayStatus()) + "」");
        }
        log.info("标记退款完成 orderNo={} 操作人={} 金额={}分 备注={}",
                order.getOrderNo(), operatorId, amount, trimmedRemark);
        statusLogService.record(orderId, OrderLogActions.PAY_REFUND,
                PayStatus.REFUNDING, PayStatus.REFUNDED,
                "退款 " + yuanText(amount) + (trimmedRemark == null ? "" : "｜" + trimmedRemark),
                now);
        // 钱退回去了必须让用户知道（只落站内消息，不占订阅消息额度，见 NotifyService.MESSAGE_ONLY）
        notifyService.refunded(orderService.require(orderId));
        return orderService.toView(orderService.require(orderId));
    }

    /** 分转「12.50 元」，只用于给用户看的文案；对账一律用分 */
    private static String yuanText(int cent) {
        return String.format("%.2f 元", cent / 100.0);
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
            // 用户提交申请后一直在等结果：只改状态等于让他自己刷页面（v3.50）
            notifyService.cancelApproved(handledOrder);
        } else {
            // 驳回必须告诉用户：他申请完一直在等结果，不通知等于让他反复刷页面。
            // 只落站内消息，不占订阅消息额度（见 NotifyService.MESSAGE_ONLY）
            notifyService.cancelRequestRejected(handledOrder);
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
        // 打印进度同样批量取一次：逐单 count 就是 20 次查询
        Map<Long, PrintService.PrintStat> printStats =
                printService.statsOf(orders.stream().map(Order::getId).toList());
        List<AdminOrderRow> rows = new ArrayList<>(orders.size());
        for (Order order : orders) {
            User user = users.get(order.getUserId());
            AddressSnapshot snapshot = order.getAddressSnapshot();
            PrintService.PrintStat stat = printStats.get(order.getId());
            // 应打张数取订单明细的份数之和（页 = 张），不取「打过的行数」
            int totalCopies = PrintService.totalCopies(order);
            int printed = stat == null ? 0 : stat.printed();
            rows.add(new AdminOrderRow(orderService.toView(order),
                    user == null ? null : user.getNickName(),
                    // 账号手机号只是「这单是谁订的」的辅助信息，订单列表按对外口径给前 3 后 2
                    user == null ? null : MaskUtil.phoneShort(user.getPhone()),
                    // 本单联系电话：取原始快照再掩码，不能用 toView 里已掩过的 address（会二次打码）
                    snapshot == null ? null : MaskUtil.phoneShort(snapshot.phone()),
                    new PrintInfo(printed, totalCopies, PrintService.stateOf(printed, totalCopies),
                            stat == null ? "" : stat.lastAt()),
                    // 列表不带打印记录（体积与查询成本都不划算），详情才填
                    List.of()));
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
     * 商家端订单行：订单本体 + 下单人信息 + 本单联系电话 + 打印进度。
     *
     * 昵称在这张表里没有冗余，列表必须能看出「这是谁订的」，
     * 否则中午接单只能靠订单号猜人。
     *
     * <p>`contactPhone` 是这单下单时确认的收货电话（掩码），与「联系顾客」拨出的号码同源；
     * `userPhone` 是账号手机号，只用来认人，不用于拨号。
     *
     * <p>`print` 是标签打印进度，`printRecords` 只在详情接口里有值（列表为 `List.of()`）：
     * 列表要回答「这单打没打」，详情才需要回答「谁在什么时候用哪台机器打的」。
     */
    public record AdminOrderRow(OrderService.OrderView order, String userNickName, String userPhone,
                                String contactPhone, PrintInfo print,
                                List<PrintService.PrintRecordRow> printRecords) {
    }

    /**
     * 打印进度。
     *
     * <p>口径都是**张**（一张纸 = 一张标签），不是「次」也不是「页」：
     * A×2 只出了一张时 `printedPages=1 / totalPages=2`，角标显示「部分 1/2」。
     * 按页去重会把它错标成「已打」，商家以为齐了 —— 少一张就是少一份饭。
     *
     * @param printState none 未打 / partial 部分 / done 打完
     * @param lastPrintedAt 最近一次成功打印时间，形如 `11-24 18:30`；没打过为空串
     */
    public record PrintInfo(int printedPages, int totalPages, String printState, String lastPrintedAt) {
    }

    /**
     * 批量接单结果。
     *
     * @param accepted 成功的订单号
     * @param skipped  跳过的原因（某一单状态不对不该拖垮整批）
     * @param warnings 成功但有风险提示的单（份数曾被超时释放）
     */
    /** @param skipped 形如「单号：原因」，直接展示给商家，不必再翻译一遍 */
    public record RejectResult(List<String> rejected, List<String> skipped) {
    }

    public record AcceptResult(List<String> accepted, List<String> skipped, List<String> warnings) {
    }
}

package com.xxxfhf.meal.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.address.Address;
import com.xxxfhf.meal.address.AddressService;
import com.xxxfhf.meal.auth.AccessGuard;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.dict.DictCodes;
import com.xxxfhf.meal.dict.DictService;
import com.xxxfhf.meal.goods.ComboDish;
import com.xxxfhf.meal.goods.SpecOption;
import com.xxxfhf.meal.menu.ComboSnapshot;
import com.xxxfhf.meal.menu.DailyMenu;
import com.xxxfhf.meal.menu.DailyMenuMapper;
import com.xxxfhf.meal.menu.DateParam;
import com.xxxfhf.meal.menu.MenuStatus;
import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.notify.NotifyService;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下单与用户端订单操作。
 *
 * 这个类里最要紧的三件事，顺序不能乱：
 * 1. **先占份再建单**，两者同一事务 —— 占了份没订单、或有订单没占份，都会让份数永久错位
 * 2. **占份用条件更新**，不做「先查剩余再减」
 * 3. **金额一律在分上算**，不出现浮点
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 一单最多几种盒饭、每种最多几份。都是防误操作的合理上限，不是业务规则 */
    private static final int MAX_ITEM_KINDS = 20;
    private static final int MAX_QTY_PER_ITEM = 20;

    /** 订单号 / 取餐码撞唯一索引时的重试次数 */
    private static final int INSERT_RETRY = 5;

    private final OrderMapper orderMapper;
    private final DailyMenuMapper menuMapper;
    private final AddressService addressService;
    private final UserMapper userMapper;
    private final ServeDateClock clock;
    private final ConfigService configService;
    private final OrderNumberService numberService;
    private final AccessGuard accessGuard;
    private final NotifyService notifyService;
    private final DictService dictService;

    public OrderService(OrderMapper orderMapper, DailyMenuMapper menuMapper,
                        AddressService addressService, UserMapper userMapper, ServeDateClock clock,
                        ConfigService configService, OrderNumberService numberService,
                        AccessGuard accessGuard, NotifyService notifyService,
                        DictService dictService) {
        this.orderMapper = orderMapper;
        this.menuMapper = menuMapper;
        this.addressService = addressService;
        this.userMapper = userMapper;
        this.clock = clock;
        this.configService = configService;
        this.numberService = numberService;
        this.accessGuard = accessGuard;
        this.notifyService = notifyService;
        this.dictService = dictService;
    }

    /* ==================== 下单 ==================== */

    /**
     * 创建订单。
     *
     * 顺序：幂等检查 → 日期与截单 → 明细与规格 → 地址或自取时段 → 金额 → 占份 → 建单。
     * 占份放在最后一步之前，是因为前面的校验失败不该先占住别人的份数。
     */
    @Transactional
    public OrderView create(String userId, CreateCommand cmd) {
        accessGuard.requireApproved(userId);
        return doCreate(userId, cmd, false);
    }

    /**
     * 代客下单（F-H06）：商家替用户下单。
     *
     * 与自助下单的区别只有两处：
     * 1. **不看截单时间** —— 顾客微信上临时说「给我来一份」，往往已经过了截单点，
     *    这恰恰是代客下单最常见的场景；截单是给顾客自助下单设的闸，不该把商家也挡在外面。
     * 2. 发起人换成操作人，但目标用户必须已通过审核，否则等于商家从后门绕过自己的审核。
     *
     * 停业、限量、菜单发布状态一概照旧校验 —— 这几条是「有没有得卖」，与谁来下单无关。
     */
    @Transactional
    public OrderView createForUser(String targetUserId, CreateCommand cmd) {
        accessGuard.requireApproved(targetUserId);
        return doCreate(targetUserId, cmd, true);
    }

    private OrderView doCreate(String userId, CreateCommand cmd, boolean skipCutoff) {
        String requestId = trimToNull(cmd.requestId());
        if (requestId != null && requestId.length() > 64) {
            throw BizException.param("请求号过长");
        }
        // 幂等：同一个请求号已经建过单，直接返回那一单，不再占份
        if (requestId != null) {
            Order existing = findByRequestId(requestId);
            if (existing != null) {
                if (!userId.equals(existing.getUserId())) {
                    throw BizException.conflict("请求号已被其他账号使用");
                }
                return toView(existing);
            }
        }

        LocalDate serveDate = DateParam.parse(cmd.serveDate(), "供餐日期");
        if (serveDate == null) {
            serveDate = clock.tomorrow();
        }
        // 只有明天可下单：后天的菜单只能看（需求 4.5）
        if (!serveDate.equals(clock.tomorrow())) {
            throw BizException.param("当前只能预订明天的餐，后天的菜单明天才开放下单");
        }
        if (!skipCutoff && clock.isCutoff(serveDate)) {
            throw BizException.conflict("今日已截单，0 点后可继续下单");
        }
        if (clock.isClosed(serveDate)) {
            throw BizException.conflict("该供餐日门店暂停供应");
        }

        List<OrderItem> items = buildItems(serveDate, cmd.items());

        String deliveryType = normalizeDeliveryType(cmd.deliveryType());
        AddressSnapshot addressSnapshot = null;
        RegionSnapshot regionSnapshot = null;
        String regionId = null;
        String pickupSlot = null;

        if ("delivery".equals(deliveryType)) {
            if (cmd.addressId() == null || cmd.addressId().isBlank()) {
                throw BizException.param("配送单必须选择收货地址");
            }
            Address address = addressService.require(cmd.addressId());
            if (!userId.equals(address.getUserId())) {
                throw BizException.forbidden("只能使用自己的收货地址");
            }
            if (!"active".equals(address.getStatus())) {
                throw BizException.conflict("该地址还在等待商家确认，暂时不能用于下单");
            }
            regionId = address.getRegionId();
            regionSnapshot = new RegionSnapshot(address.getRegionId(), address.getRegionName());
            addressSnapshot = new AddressSnapshot(address.getContactName(), address.getPhone(),
                    address.getDetail(), address.getRegionName(), address.getTag());
        } else {
            pickupSlot = requirePickupSlot(cmd.pickupSlot());
            // 自取单没有地址可留电话，商家找人是靠账号手机号（需求 4.8）
            User user = userMapper.selectById(userId);
            String phone = user == null ? null : user.getPhone();
            if (phone == null || phone.isBlank()) {
                throw BizException.param("请先在「我的」页填写手机号，方便商家联系你取餐");
            }
            // 自取单也写快照：只填联系人（昵称）与电话，地址区域留空。
            // 之后用户改了账号手机号，历史单上的联系电话也不变 —— 与配送单同一原则
            addressSnapshot = new AddressSnapshot(user.getNickName(), phone.trim(), null, null, null);
        }

        int goodsAmount = 0;
        int specAmount = 0;
        for (OrderItem item : items) {
            goodsAmount += item.lineGoodsAmount();
            specAmount += item.lineSpecAmount();
        }
        int deliveryFee = "delivery".equals(deliveryType)
                ? Math.max(0, configService.getInt(ConfigKey.DELIVERY_FEE, 0)) : 0;

        // 占份：条件更新，任何一项占到 0 行就整单失败并回滚
        occupyStock(serveDate, items);

        Order order = new Order();
        order.setRequestId(requestId);
        order.setUserId(userId);
        order.setServeDate(serveDate);
        order.setDeliveryType(deliveryType);
        order.setRegionId(regionId);
        order.setRegionSnapshot(regionSnapshot);
        order.setAddressSnapshot(addressSnapshot);
        order.setItems(items);
        order.setGoodsAmount(goodsAmount);
        order.setSpecAmount(specAmount);
        order.setDeliveryFee(deliveryFee);
        order.setTotalAmount(goodsAmount + specAmount + deliveryFee);
        order.setStatus(OrderStatus.PENDING_ACCEPT);
        order.setCancelRequested(false);
        order.setPayStatus(PayStatus.UNPAID);
        order.setPayMethod("offline_transfer");
        order.setPickupSlot(pickupSlot);
        order.setStockReleased(false);
        order.setRemark(truncate(cmd.remark(), 255));
        order.setCreatedAt(LocalDateTime.now());

        insertWithRetry(order, serveDate);

        log.info("创建订单 orderNo={} userId={} serveDate={} 金额={}分",
                order.getOrderNo(), userId, serveDate, order.getTotalAmount());
        // 通知商家有新单。事务提交后才真正发出，发不出去也不影响这单已经建成
        notifyService.newOrder(order);
        return toView(order);
    }

    /**
     * 建单，撞唯一索引时重试。
     *
     * order_no 是「取当日最大流水 + 1」，并发下必然可能撞；pickup_code 同理。
     * 两者都不能靠应用层加锁解决，所以生成 → 插入 → 撞了就换一个再来。
     */
    private void insertWithRetry(Order order, LocalDate serveDate) {
        for (int attempt = 1; attempt <= INSERT_RETRY; attempt++) {
            order.setOrderNo(numberService.nextOrderNo());
            order.setPickupCode(numberService.nextPickupCode(serveDate));
            try {
                orderMapper.insert(order);
                return;
            } catch (DuplicateKeyException ex) {
                // 重试前必须清掉已回填的主键，否则第二次插入还是同一条记录
                order.setId(null);
                log.warn("订单号或取餐码冲突，第 {} 次重试（orderNo={} code={}）",
                        attempt, order.getOrderNo(), order.getPickupCode());
            }
        }
        // 交给上层事务回滚：此时占份也会一起撤销，不会出现「占了份没订单」
        throw BizException.conflict("下单人数较多，请稍后重试");
    }

    /** 明细校验与快照组装。只认**已发布**菜单里的盒饭。 */
    private List<OrderItem> buildItems(LocalDate serveDate, List<ItemInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw BizException.param("请先选择盒饭");
        }
        if (inputs.size() > MAX_ITEM_KINDS) {
            throw BizException.param("一单最多 " + MAX_ITEM_KINDS + " 种盒饭");
        }

        Map<String, DailyMenu> byCombo = new HashMap<>();
        for (DailyMenu menu : menuMapper.selectList(Wrappers.<DailyMenu>lambdaQuery()
                .eq(DailyMenu::getServeDate, serveDate)
                .eq(DailyMenu::getStatus, MenuStatus.PUBLISHED))) {
            byCombo.put(menu.getComboId(), menu);
        }

        List<OrderItem> result = new ArrayList<>(inputs.size());
        Set<String> seen = new HashSet<>();
        for (ItemInput input : inputs) {
            String comboId = input == null ? "" : trim(input.comboId());
            if (comboId.isEmpty()) {
                throw BizException.param("明细缺少盒饭 ID");
            }
            if (!seen.add(comboId)) {
                throw BizException.param("同一盒饭请合并份数，不要重复提交");
            }
            DailyMenu menu = byCombo.get(comboId);
            if (menu == null) {
                throw BizException.conflict("所选盒饭已不在该供餐日的供应菜单里，请刷新后重选");
            }
            int qty = input.qty() == null ? 1 : input.qty();
            if (qty < 1) {
                throw BizException.param("份数至少为 1");
            }
            if (qty > MAX_QTY_PER_ITEM) {
                throw BizException.param("单个盒饭一次最多 " + MAX_QTY_PER_ITEM + " 份");
            }

            ComboSnapshot snapshot = menu.getComboSnapshot();
            if (snapshot == null) {
                throw BizException.conflict("菜单数据异常，请联系商家");
            }
            List<OrderItem.Spec> specs = resolveSpecs(snapshot, input.optionIds());
            result.add(new OrderItem(comboId, snapshot.name(), menu.getPriceSnapshot(), qty,
                    snapshot.dishes() == null ? List.of() : snapshot.dishes(), specs));
        }
        return result;
    }

    /**
     * 规格校验与快照。
     *
     * 按**组**逐个校验：必选组必须选一项，同组只能选一项，选了的必须真在组里。
     * 快照存名称与差价，不存选项 ID —— 商家删掉选项后，历史订单仍能还原当时选的是什么。
     */
    private List<OrderItem.Spec> resolveSpecs(ComboSnapshot snapshot, List<String> optionIds) {
        List<String> chosen = new ArrayList<>();
        if (optionIds != null) {
            for (String id : optionIds) {
                String trimmed = trim(id);
                if (!trimmed.isEmpty() && !chosen.contains(trimmed)) {
                    chosen.add(trimmed);
                }
            }
        }

        List<ComboSnapshot.SpecGroupSnapshot> groups =
                snapshot.specGroups() == null ? List.of() : snapshot.specGroups();
        List<OrderItem.Spec> specs = new ArrayList<>();
        Set<String> matched = new HashSet<>();

        for (ComboSnapshot.SpecGroupSnapshot group : groups) {
            List<SpecOption> hit = new ArrayList<>();
            if (group.options() != null) {
                for (SpecOption option : group.options()) {
                    if (chosen.contains(option.optionId())) {
                        hit.add(option);
                    }
                }
            }
            if (hit.isEmpty()) {
                if (Boolean.TRUE.equals(group.required())) {
                    throw BizException.param("请选择「" + group.name() + "」");
                }
                continue;
            }
            if (hit.size() > 1) {
                throw BizException.param("「" + group.name() + "」只能选一项");
            }
            SpecOption option = hit.get(0);
            matched.add(option.optionId());
            // countInPrep 随快照一起落到订单：备菜统计要按下单那一刻的口径算
            specs.add(new OrderItem.Spec(group.name(), option.name(), option.deltaOrZero(),
                    Boolean.TRUE.equals(group.countInPrep())));
        }

        for (String id : chosen) {
            if (!matched.contains(id)) {
                throw BizException.conflict("所选规格已失效，请刷新后重新选择");
            }
        }
        return specs;
    }

    private void occupyStock(LocalDate serveDate, List<OrderItem> items) {
        for (OrderItem item : items) {
            int affected = menuMapper.occupy(serveDate, item.comboId(), item.qtyOrZero());
            if (affected == 0) {
                throw BizException.conflict("「" + item.name() + "」已售罄，请调整份数后重试");
            }
        }
    }

    /** 释放份数。与状态变更同事务，调用方必须在同一个 @Transactional 里。 */
    void releaseStock(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }
        for (OrderItem item : order.getItems()) {
            menuMapper.release(order.getServeDate(), item.comboId(), item.qtyOrZero());
        }
    }

    /* ==================== 查询 ==================== */

    public IPage<OrderView> mine(String userId, String status, int page, int pageSize) {
        Page<Order> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        IPage<Order> result = orderMapper.selectPage(pager, Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(status != null && !status.isBlank(), Order::getStatus, status)
                .orderByDesc(Order::getCreatedAt));

        Page<OrderView> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(result.getRecords().stream().map(this::toView).toList());
        return mapped;
    }

    public OrderView detail(String userId, String orderId) {
        return toView(requireOwned(orderId, userId));
    }

    /* ==================== 用户操作 ==================== */

    /** 用户点「我已完成付款」→ 声称已付款。**不是已收款**，钱还没核对。 */
    @Transactional
    public OrderView claimPaid(String userId, String orderId) {
        Order order = requireOwned(orderId, userId);
        if (OrderStatus.isFinal(order.getStatus())) {
            throw BizException.conflict("订单已结束（" + OrderStatus.label(order.getStatus()) + "），无法标记付款");
        }
        if (!PayStatus.UNPAID.equals(order.getPayStatus())) {
            throw BizException.conflict("当前收款状态是「" + PayStatus.label(order.getPayStatus())
                    + "」，无需重复操作");
        }

        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.CLAIMED)
                .set(Order::getClaimedAt, LocalDateTime.now())
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, PayStatus.UNPAID));
        if (affected == 0) {
            throw BizException.conflict("收款状态已变化，请刷新后重试");
        }
        log.info("用户声称已付款 orderNo={} userId={}", order.getOrderNo(), userId);
        return toView(require(orderId));
    }

    /**
     * 取消 / 申请取消。
     *
     * 三档，按需求 4.9「取消规则」实现：
     * - 截单前 + 待接单 → 直接取消，释放份数
     * - 截单后 + 待接单 → 只能提交申请，等商家批
     * - 其余（已接单及之后）→ 拒绝，引导联系商家
     */
    @Transactional
    public OrderView cancel(String userId, String orderId, String reason) {
        Order order = requireOwned(orderId, userId);
        if (OrderStatus.isFinal(order.getStatus())) {
            throw BizException.conflict("订单已" + OrderStatus.label(order.getStatus()) + "，不能再取消");
        }
        if (!OrderStatus.PENDING_ACCEPT.equals(order.getStatus())) {
            throw BizException.conflict("商家已接单，无法自行取消，请联系商家处理");
        }

        String trimmedReason = truncate(reason, 200);
        boolean cutoff = clock.isCutoff(order.getServeDate());

        if (!cutoff) {
            // 截单前：直接取消。条件更新防并发（另一个入口可能同时拒单）
            int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, OrderStatus.CANCELLED)
                    .set(Order::getCancelRequested, false)
                    .eq(Order::getId, orderId)
                    .eq(Order::getStatus, OrderStatus.PENDING_ACCEPT));
            if (affected == 0) {
                throw BizException.conflict("订单状态已变化，请刷新后重试");
            }
            releaseStock(order);
            moveToRefundingIfPaid(orderId);
            log.info("用户取消订单 orderNo={}（截单前）", order.getOrderNo());
            Order cancelled = require(orderId);
            // 通知商家，免得白做一份
            notifyService.cancelled(cancelled);
            return toView(cancelled);
        }

        // 截单后：只能申请
        if (Boolean.TRUE.equals(order.getCancelRequested())) {
            throw BizException.conflict("已提交取消申请，等待商家处理");
        }
        CancelRequest request = new CancelRequest(trimmedReason, LocalDateTime.now(), null, null, null, null);
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getCancelRequested, true)
                .set(Order::getCancelRequest, request)
                .set(Order::getCancelRequestedAt, LocalDateTime.now())
                .eq(Order::getId, orderId)
                .eq(Order::getCancelRequested, false));
        if (affected == 0) {
            throw BizException.conflict("已提交取消申请，等待商家处理");
        }
        log.info("用户提交取消申请 orderNo={} 原因={}", order.getOrderNo(), trimmedReason);
        Order requested = require(orderId);
        // 通知商家：不推的话这条申请只会躺在订单列表里等人翻（工作台角标是第二道保险）
        notifyService.cancelRequested(requested);
        return toView(requested);
    }

    /**
     * 已收到钱的单被取消 / 拒单，强制转「待退款」。
     *
     * claimed 也算：用户说付了但商家没核对，这时既不能认定已收也不能当没收到。
     * 统一挂「待退款」让商家去核对，比两边都不动更容易收口。
     */
    void moveToRefundingIfPaid(String orderId) {
        Order order = require(orderId);
        String pay = order.getPayStatus();
        if (!PayStatus.CLAIMED.equals(pay) && !PayStatus.PAID.equals(pay)) {
            return;
        }
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.REFUNDING)
                .eq(Order::getId, orderId)
                .in(Order::getPayStatus, List.of(PayStatus.CLAIMED, PayStatus.PAID)));
        log.info("订单转入待退款 orderNo={} 原收款状态={}", order.getOrderNo(), pay);
    }

    /**
     * 超时未付款：释放份数，订单**状态不变**（仍在待接单）。
     *
     * 为什么不是直接取消订单：截单在供餐日前一天，商家多半次日上午统一接单，
     * 直接取消会把截单前下的单几乎全废掉（技术文档 4.4）。
     *
     * 由定时任务逐单调，每单一个事务：一单失败不影响其它单。
     *
     * @return 是否真的释放了（已经不是待接单 / 用户声称已付款 / 已释放过，都返回 false）
     */
    @Transactional
    public boolean releaseForTimeout(String orderId) {
        Order order = require(orderId);
        if (!OrderStatus.PENDING_ACCEPT.equals(order.getStatus())
                || Boolean.TRUE.equals(order.getStockReleased())
                || PayStatus.userClaimed(order.getPayStatus())) {
            return false;
        }
        // 条件更新：pay_status = 'unpaid' 写进 WHERE，避免「查的时候没付款、更新的时候已声称」
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStockReleased, true)
                .set(Order::getStockReleasedAt, LocalDateTime.now())
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PENDING_ACCEPT)
                .eq(Order::getPayStatus, PayStatus.UNPAID)
                .eq(Order::getStockReleased, false));
        if (affected == 0) {
            return false;
        }
        releaseStock(order);
        log.info("超时未付款释放份数 orderNo={} serveDate={}", order.getOrderNo(), order.getServeDate());
        return true;
    }    /* ==================== 内部 ==================== */

    Order require(String orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.notFound("订单不存在");
        }
        return order;
    }

    private Order requireOwned(String orderId, String userId) {
        Order order = require(orderId);
        if (!order.getUserId().equals(userId)) {
            throw BizException.forbidden("只能查看自己的订单");
        }
        return order;
    }

    private Order findByRequestId(String requestId) {
        List<Order> list = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getRequestId, requestId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private String normalizeDeliveryType(String raw) {
        String type = raw == null ? "" : raw.trim();
        if (type.isEmpty()) {
            return "pickup";
        }
        if (!"pickup".equals(type) && !"delivery".equals(type)) {
            throw BizException.param("取餐方式只能是 pickup（自取）或 delivery（配送）");
        }
        return type;
    }

    /** 自取时段必须来自全局配置，不接受前端自由填 —— 商家要按它安排出餐节奏。 */
    private String requirePickupSlot(String raw) {
        String slot = trim(raw);
        if (slot.isEmpty()) {
            throw BizException.param("自取单请选择取餐时段");
        }
        List<String> slots = configService.getStringList(ConfigKey.PICKUP_SLOTS);
        if (!slots.contains(slot)) {
            throw BizException.param("取餐时段已调整，请重新选择");
        }
        return slot;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    OrderView toView(Order order) {
        String status = order.getStatus();
        String payStatus = order.getPayStatus();
        // 中文名与配色优先取字典：商家改个说法不用发版。
        // 字典缺项时回退到代码里的静态标签，绝不出现空标签。
        // ready 例外：一个状态两种叫法（自取「待取餐」、配送「配送中」），
        // 字典一项只能存一个名，这一项由代码按取送方式算，不吃字典
        String statusLabel = OrderStatus.READY.equals(status)
                ? OrderStatus.label(status, order.getDeliveryType())
                : dictService.label(DictCodes.ORDER_STATUS, status,
                        OrderStatus.label(status, order.getDeliveryType()));
        return new OrderView(order.getId(), order.getOrderNo(), order.getServeDate(),
                order.getDeliveryType(), status,
                statusLabel,
                dictService.tagType(DictCodes.ORDER_STATUS, status),
                payStatus,
                dictService.label(DictCodes.PAY_STATUS, payStatus, PayStatus.label(payStatus)),
                dictService.tagType(DictCodes.PAY_STATUS, payStatus),
                order.getItems(), order.getGoodsAmount(), order.getSpecAmount(),
                order.getDeliveryFee(), order.getTotalAmount(),
                order.getPickupCode(), order.getPickupSlot(),
                order.getRegionSnapshot(), order.getAddressSnapshot(), order.getRemark(),
                Boolean.TRUE.equals(order.getCancelRequested()), order.getCancelRequest(),
                order.getRejectReason(), Boolean.TRUE.equals(order.getStockReleased()),
                order.getCreatedAt(), order.getCancelRequestedAt());
    }

    /* ==================== 入参 / 出参 ==================== */

    /**
     * 下单入参。
     *
     * @param serveDate    供餐日，留空按明天；传后天会被拒（后天只能看）
     * @param deliveryType pickup / delivery，留空按自取
     * @param addressId    配送单必填；自取单忽略
     * @param pickupSlot   自取单必填，必须是配置里的时段之一
     * @param requestId    幂等号，前端每次进入确认页生成一个
     */
    public record CreateCommand(String serveDate, String deliveryType, String addressId,
                                String pickupSlot, String remark,
                                List<ItemInput> items, String requestId) {
    }

    /**
     * @param optionIds 所选规格选项 ID。**不传组 ID**：一个选项必属一组，
     *                  由服务端按快照反向定位所属组，前端少一层映射就少一处出错
     */
    public record ItemInput(String comboId, Integer qty, List<String> optionIds) {
    }

    /**
     * 订单视图。用户端与商家端共用，不暴露 requestId 这类内部字段。
     *
     * 拒单原因、取消申请、取消申请中标记都在这里 —— 需求明确要求用户能看到这些。
     *
     * @param statusTag    状态配色（字典配置），前端按它上色，查不到为 null
     * @param payStatusTag 收款状态配色，同上
     * @param cancelRequestedAt 申请提交时间，前端用来显示「已等待多久」
     */
    public record OrderView(String orderId, String orderNo, LocalDate serveDate, String deliveryType,
                            String status, String statusLabel, String statusTag,
                            String payStatus, String payStatusLabel, String payStatusTag,
                            List<OrderItem> items, Integer goodsAmount, Integer specAmount,
                            Integer deliveryFee, Integer totalAmount,
                            String pickupCode, String pickupSlot,
                            RegionSnapshot region, AddressSnapshot address, String remark,
                            boolean cancelRequested, CancelRequest cancelRequest,
                            String rejectReason, boolean stockReleased,
                            LocalDateTime createdAt, LocalDateTime cancelRequestedAt) {
    }

    /** 供备菜统计等场景复用的明细行（避免各处再解一次 JSON）。 */
    public static List<OrderItem> itemsOf(Order order) {
        return order.getItems() == null ? List.of() : order.getItems();
    }

    /** 明细里的菜品份数合计辅助（备菜按 dishId 聚合时用）。 */
    public static List<ComboDish> dishesOf(OrderItem item) {
        return item.dishes() == null ? List.of() : item.dishes();
    }
}

package com.xxxfhf.meal.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.address.Address;
import com.xxxfhf.meal.address.AddressService;
import com.xxxfhf.meal.auth.AccessGuard;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ContentSecurityService;
import com.xxxfhf.meal.common.MaskUtil;
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
import java.util.TreeSet;

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
    private final OrderStatusLogService statusLogService;
    private final ContentSecurityService contentSecurity;

    public OrderService(OrderMapper orderMapper, DailyMenuMapper menuMapper,
                        AddressService addressService, UserMapper userMapper, ServeDateClock clock,
                        ConfigService configService, OrderNumberService numberService,
                        AccessGuard accessGuard, NotifyService notifyService,
                        DictService dictService, OrderStatusLogService statusLogService,
                        ContentSecurityService contentSecurity) {
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
        this.statusLogService = statusLogService;
        this.contentSecurity = contentSecurity;
    }

    /* ==================== 下单 ==================== */

    /**
     * 创建订单。
     *
     * 顺序：幂等检查 → 日期与截单 → 明细与规格 → 地址或自取时段 → 金额 → 占份 → 建单。
     * 占份放在最后一步之前，是因为前面的校验失败不该先占住别人的份数。
     */
    @Transactional
    public OrderView create(Long userId, CreateCommand cmd) {
        accessGuard.requireApproved(userId);
        return doCreate(userId, cmd, false, false);
    }

    /**
     * 代客下单（F-H06）：商家替用户下单。
     *
     * 与自助下单的区别只有三处：
     * 1. **不看截单时间** —— 顾客微信上临时说「给我来一份」，往往已经过了截单点，
     *    这恰恰是代客下单最常见的场景；截单是给顾客自助下单设的闸，不该把商家也挡在外面。
     * 2. 发起人换成操作人，但目标用户必须已通过审核，否则等于商家从后门绕过自己的审核。
     * 3. **可以强制下单**（{@code force}，N-44）—— 地址已停用或所在区域已停用时仍然放行。
     *    商家在电话里已经和顾客确认过送哪，拦死等于用系统规则否定线下判断；
     *    放行会在时间轴留一条「商家强制下单：<原因>」，事后能复盘。
     *
     * 停业、限量、菜单发布状态一概照旧校验 —— 这几条是「有没有得卖」，与谁来下单无关。
     */
    @Transactional
    public OrderView createForUser(Long targetUserId, CreateCommand cmd, boolean force) {
        accessGuard.requireApproved(targetUserId);
        OrderView view = doCreate(targetUserId, cmd, true, force);
        // 代客下单的对象多半是不会用小程序的人：不主动说一声，他既不知道单已下上，
        // 也不知道几点取、多少钱。只落站内消息，不推订阅（多半没授权）—— v3.50
        if (view != null && view.orderId() != null) {
            notifyService.proxyCreated(orderMapper.selectById(view.orderId()));
        }
        return view;
    }

    private OrderView doCreate(Long userId, CreateCommand cmd, boolean skipCutoff, boolean force) {
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
        // 只有明天可下单：后天只能预览，今天 / 过去则是已经结束（需求 4.5、4.6）
        LocalDate tomorrow = clock.tomorrow();
        if (serveDate.isBefore(tomorrow)) {
            throw BizException.conflict("该供餐日的下单已结束，请重新选择");
        }
        if (serveDate.isAfter(tomorrow)) {
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
        Long regionId = null;
        String pickupSlot = null;
        /** 强制下单时的原因，用于时间轴留痕；正常下单为 null */
        String forcedReason = null;

        if ("delivery".equals(deliveryType)) {
            if (cmd.addressId() == null) {
                throw BizException.param("配送单必须选择收货地址");
            }
            // 地址能不能用只看这一处（可用性口径见技术文档 4.17）：
            // 审核状态 × 地址启用 × 区域启用，三者在 AddressService 里相乘
            AddressService.AddressCheck check =
                    addressService.requireUsableForOrder(cmd.addressId(), userId, force);
            Address address = check.address();
            if (!check.usability().usable()) {
                // 能走到这里只可能是 force = 真（否则上面已经抛了），记下来是为了留痕
                forcedReason = check.usability().label();
            }
            regionId = address.getRegionId();
            regionSnapshot = new RegionSnapshot(address.getRegionId(), address.getRegionName());
            addressSnapshot = new AddressSnapshot(address.getContactName(), address.getPhone(),
                    address.getDetail(), address.getRegionName(), address.getTag());
        } else {
            pickupSlot = requirePickupSlot(cmd.pickupSlot());
            // 自取单没有地址，电话不强制填：取餐靠 4 位取餐码核销，商家不需要电话也能履约
            // （隐私整改 P0-08；微信《收集用户手机号规范》里「到店点餐/取餐」手机号非必要）
            addressSnapshot = pickupSnapshot(userMapper.selectById(userId));
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
        // 收款方式不在这里预置：下单时还没付钱，方式由用户点「我已完成付款」时选（需求 4.10）
        order.setPickupSlot(pickupSlot);
        order.setStockReleased(false);
        String remark = trimToNull(cmd.remark());
        requireContentOk(userId, remark, "备注包含违规内容，请修改后再提交");
        order.setRemark(truncate(remark, 255));
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now);

        insertWithRetry(order, serveDate);
        // 时间轴第一条。代客下单（商家替用户下）时操作人自动记成商家、来源记 admin ——
        // 留痕要记「谁点的」，不是「订单归谁」。
        // 强制下单（地址不可用仍然放行）额外记一条备注，事后能看出这单没走常规校验
        String createRemark = forcedReason == null ? null
                : truncate("商家强制下单：" + forcedReason, OrderStatusLogService.MAX_REMARK);
        statusLogService.record(order.getId(), OrderLogActions.ORDER_CREATE, null,
                OrderStatus.PENDING_ACCEPT, createRemark, now);

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

        Map<Long, DailyMenu> byCombo = new HashMap<>();
        for (DailyMenu menu : menuMapper.selectList(Wrappers.<DailyMenu>lambdaQuery()
                .eq(DailyMenu::getServeDate, serveDate)
                .eq(DailyMenu::getStatus, MenuStatus.PUBLISHED))) {
            byCombo.put(menu.getComboId(), menu);
        }

        List<OrderItem> result = new ArrayList<>(inputs.size());
        Set<String> seen = new HashSet<>();
        for (ItemInput input : inputs) {
            Long comboId = input == null ? null : input.comboId();
            if (comboId == null) {
                throw BizException.param("明细缺少盒饭 ID");
            }
            // 去重按「盒饭 + 规格」算：同盒饭选不同规格（白米饭 / 杂粮饭）是两行，允许同单；
            // 只有盒饭和规格都相同才算重复提交。口径必须与小程序购物车 lineKey 一致
            if (!seen.add(itemKey(comboId, input.optionIds()))) {
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
     * 明细行的去重标识：盒饭 ID + 排序后的规格选项（顺序无关、去空格与重复项）。
     *
     * 前端购物车按「盒饭 + 规格」分两行（cart.js 的 lineKey 同口径），
     * 服务端去重若只按 comboId，会把用户同盒饭买两种主食误判为重复提交。
     */
    private static String itemKey(Long comboId, List<String> optionIds) {
        Set<String> unique = new TreeSet<>();
        if (optionIds != null) {
            for (String id : optionIds) {
                String trimmed = trim(id);
                if (!trimmed.isEmpty()) {
                    unique.add(trimmed);
                }
            }
        }
        return comboId + "|" + String.join(",", unique);
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

    /**
     * 释放份数（幂等）。
     *
     * <p>先抢占 {@code stock_released}，只有抢到的那一次才真正减库存：同一单可能被两条路径
     * 先后要求释放（拒单后用户又申请取消、定时任务与商家操作交叉），减两次等于白送份数，
     * 而 {@code GREATEST(..., 0)} 只防负数、不防重复扣减。
     *
     * <p>与状态变更同事务，调用方必须在同一个 @Transactional 里。
     */
    void releaseStock(Order order) {
        if (Boolean.TRUE.equals(order.getStockReleased())) {
            // 调用方已经知道释放过了，连抢占都不用做
            return;
        }
        int claimed = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStockReleased, true)
                .set(Order::getStockReleasedAt, LocalDateTime.now())
                .eq(Order::getId, order.getId())
                .eq(Order::getStockReleased, false));
        if (claimed == 0) {
            log.info("份数已释放过，跳过 orderNo={}", order.getOrderNo());
            return;
        }
        doReleaseStock(order);
    }

    /** 真正把份数加回去。只有 {@link #releaseStock} 抢占成功、或超时任务已经自己抢过的那条路径能调。 */
    private void doReleaseStock(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }
        for (OrderItem item : order.getItems()) {
            menuMapper.release(order.getServeDate(), item.comboId(), item.qtyOrZero());
        }
    }

    /* ==================== 查询 ==================== */

    public IPage<OrderView> mine(Long userId, String status, int page, int pageSize) {
        Page<Order> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        IPage<Order> result = orderMapper.selectPage(pager, Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(status != null && !status.isBlank(), Order::getStatus, status)
                .orderByDesc(Order::getCreatedAt)
                .orderByDesc(Order::getId));

        Page<OrderView> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(result.getRecords().stream().map(this::toView).toList());
        return mapped;
    }

    public OrderView detail(Long userId, Long orderId) {
        return toView(requireOwned(orderId, userId));
    }

    /**
     * 我的订单时间轴：只返回订单本身的流转节点。
     *
     * <p>归属校验与 {@link #detail} 同一口径 —— 时间轴里有操作人、备注，能看时间轴
     * 就等于能看到这一单的全部处理过程，不能只看「有权限读日志」。
     *
     * <p>过滤规则见 {@link OrderStatusLogService#listOrderLine}：不含 {@code event.*}。
     */
    public List<OrderStatusLogService.Row> timeline(Long userId, Long orderId) {
        requireOwned(orderId, userId);
        return statusLogService.listOrderLine(orderId);
    }

    /* ==================== 用户操作 ==================== */

    /**
     * 用户点「我已完成付款」→ 声称已付款。**不是已收款**，钱还没核对。
     *
     * <p>收款方式必填（需求 4.10）：只有付款人知道用的什么方式，缺值直接 400，
     * 不落「已声称但没记方式」的半截状态 —— 那种单子事后对账时无法断定是漏填还是没填。
     *
     * <p>合法值以字典 {@code pay_method} 为准（商家可自己加项），不再写死三个常量。
     */
    @Transactional
    public OrderView claimPaid(Long userId, Long orderId, String payMethodRaw) {
        Order order = requireOwned(orderId, userId);
        String payMethod = dictService.requireValue(DictCodes.PAY_METHOD, "收款方式",
                payMethodRaw, "标记付款");
        if (OrderStatus.isFinal(order.getStatus())) {
            throw BizException.conflict("订单已结束（" + OrderStatus.label(order.getStatus()) + "），无法标记付款");
        }
        if (!PayStatus.UNPAID.equals(order.getPayStatus())) {
            throw BizException.conflict("当前收款状态是「" + PayStatus.label(order.getPayStatus())
                    + "」，无需重复操作");
        }

        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.CLAIMED)
                .set(Order::getClaimedAt, now)
                .set(Order::getPayMethod, payMethod)
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, PayStatus.UNPAID));
        if (affected == 0) {
            throw BizException.conflict("收款状态已变化，请刷新后重试");
        }
        // 用户侧最关键的一条记录：「我八点就点了已付款」「我用微信付的」现在都有据可查
        statusLogService.record(orderId, OrderLogActions.PAY_CLAIM,
                PayStatus.UNPAID, PayStatus.CLAIMED, null, payMethod, now);
        log.info("用户声称已付款 orderNo={} userId={} 方式={}",
                order.getOrderNo(), userId, payMethod);
        // 只有工作台角标的话，商家不主动刷新就不知道有人在等核对（v3.50）
        notifyService.payClaimed(require(orderId));
        return toView(require(orderId));
    }

    /**
     * 取消 / 申请取消。
     *
     * 三档，按需求 4.9「取消规则」实现：
     * - 待接单（不论截单前后）→ 直接取消，释放份数：商家还没接，餐没开始做
     * - 已接单 + 未过截单 → 直接取消，释放份数（商家提前在截单前接单的情况）
     * - 已接单 + 已过截单 → 只能提交申请，等商家批
     * - 出餐及之后 → 拒绝，引导联系商家
     */
    @Transactional
    public OrderView cancel(Long userId, Long orderId, String reason) {
        Order order = requireOwned(orderId, userId);
        // 能不能自己取消由转移表说了算；终态、出餐后与「已过截单的已接单」是三种不同的答复，
        // 所以允许判定统一、文案仍然分开
        if (!OrderTransition.CANCEL_BY_USER.allows(order.getStatus())) {
            if (OrderStatus.isFinal(order.getStatus())) {
                throw BizException.conflict("订单已" + OrderStatus.label(order.getStatus()) + "，不能再取消");
            }
            throw BizException.conflict("餐已出餐，无法取消，请联系商家处理");
        }

        String trimmedReason = truncate(reason, 200);
        requireContentOk(userId, trimmedReason, "取消原因包含违规内容，请修改后再提交");
        boolean cutoff = clock.isCutoff(order.getServeDate());
        // 待接单随时可取消；已接单要看截单点：没过截单直接取消，过了只能申请
        boolean directCancel = OrderStatus.PENDING_ACCEPT.equals(order.getStatus()) || !cutoff;

        if (directCancel) {
            // 截单前：直接取消。条件更新防并发（另一个入口可能同时拒单）
            LocalDateTime now = LocalDateTime.now();
            int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, OrderTransition.CANCEL_BY_USER.target())
                    .set(Order::getCancelRequested, false)
                    .eq(Order::getId, orderId)
                    .in(Order::getStatus, OrderTransition.CANCEL_BY_USER.fromStatuses()));
            if (affected == 0) {
                throw BizException.conflict("订单状态已变化，请刷新后重试");
            }
            // 先记流转再动份数与收款：时间轴上「已取消」要排在「转入待退款」之前，
            // 两条都带同一个 now，靠毫秒级先后保持顺序
            statusLogService.record(orderId, OrderTransition.CANCEL_BY_USER.logAction(),
                    order.getStatus(), OrderTransition.CANCEL_BY_USER.target(),
                    trimmedReason, now);
            releaseStock(order);
            moveToRefundingIfPaid(orderId);
            log.info("用户取消订单 orderNo={}（原状态 {}，截单{}）", order.getOrderNo(),
                    order.getStatus(), cutoff ? "后" : "前");
            Order cancelled = require(orderId);
            // 通知商家，免得白做一份
            notifyService.cancelled(cancelled);
            return toView(cancelled);
        }

        // 已接单且已过截单：只能申请。待接单与截单前的已接单都在上面直接取消掉了
        if (Boolean.TRUE.equals(order.getCancelRequested())) {
            throw BizException.conflict("已提交取消申请，等待商家处理");
        }
        LocalDateTime appliedAt = LocalDateTime.now();
        CancelRequest request = new CancelRequest(trimmedReason, appliedAt, null, null, null, null);
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getCancelRequested, true)
                .set(Order::getCancelRequest, request)
                .set(Order::getCancelRequestedAt, appliedAt)
                .eq(Order::getId, orderId)
                .eq(Order::getCancelRequested, false));
        if (affected == 0) {
            throw BizException.conflict("已提交取消申请，等待商家处理");
        }
        // 事件类：订单状态没变，但这条必须留 —— 否则「申请取消却没有结果」查不到源头
        statusLogService.record(orderId, OrderLogActions.EVENT_CANCEL_APPLY,
                null, null, trimmedReason, appliedAt);
        log.info("用户提交取消申请 orderNo={} 原因={}", order.getOrderNo(), trimmedReason);
        Order requested = require(orderId);
        // 通知商家：不推的话这条申请只会躺在订单列表里等人翻（工作台角标是第二道保险）
        notifyService.cancelRequested(requested);
        return toView(requested);
    }

    /**
     * 供餐日已过、仍是待接单 → 系统关单（v3.50 / 需求 4.9 / `F-E11`）。
     *
     * <p>待接单不是终态，但供餐日过去以后它已经不可能被履约。不关的话它会永远挂在
     * 「待接单」、永远计入应收，工作台待办也永远不归零（这是真正的错账来源）。
     *
     * <p>复用 {@code cancelled} 而不是新增「已过期」状态：差异用动作码
     * {@link OrderLogActions#ORDER_EXPIRE} 与消息文案表达，状态机、字典、对账、
     * 备菜、两端页签都不必再为第七个状态改一遍。
     *
     * <p>钱的处置与取消完全同一条规则：`claimed` / `paid` 一律转「待退款」——
     * 否则单子关了、钱还记在已收上，对账会凭空多出一笔。
     *
     * @return 是否真的关闭（已被接单 / 还没到供餐日次日 / 并发下已被别人改过，都返回 false）
     */
    @Transactional
    public boolean expirePending(Long orderId) {
        Order order = require(orderId);
        if (!OrderStatus.PENDING_ACCEPT.equals(order.getStatus())) {
            return false;
        }
        // 只关「供餐日 < 今天」的单：供餐日当天全天都允许商家补接单，
        // 0 点刚过就关会把当天要送的餐全废掉
        if (order.getServeDate() == null || !order.getServeDate().isBefore(clock.today())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, OrderStatus.CANCELLED)
                .set(Order::getCancelRequested, false)
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PENDING_ACCEPT));
        if (affected == 0) {
            return false;
        }
        statusLogService.record(orderId, OrderLogActions.ORDER_EXPIRE,
                OrderStatus.PENDING_ACCEPT, OrderStatus.CANCELLED, "供餐日已过且未接单", now);
        releaseStock(order);
        moveToRefundingIfPaid(orderId);
        log.info("过期未接单关闭 orderNo={} serveDate={}", order.getOrderNo(), order.getServeDate());
        // 事务提交后才真正写入，写失败也不影响关单本身
        notifyService.orderExpired(require(orderId));
        return true;
    }

    /**
     * 已收到钱的单被取消 / 拒单，强制转「待退款」。
     *
     * claimed 也算：用户说付了但商家没核对，这时既不能认定已收也不能当没收到。
     * 统一挂「待退款」让商家去核对，比两边都不动更容易收口。
     */
    void moveToRefundingIfPaid(Long orderId) {
        Order order = require(orderId);
        String pay = order.getPayStatus();
        if (!PayStatus.CLAIMED.equals(pay) && !PayStatus.PAID.equals(pay)) {
            return;
        }
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getPayStatus, PayStatus.REFUNDING)
                .eq(Order::getId, orderId)
                .in(Order::getPayStatus, List.of(PayStatus.CLAIMED, PayStatus.PAID)));
        if (affected == 0) {
            // 并发下另一条路径已经改过收款状态，这里没真正发生流转就不该留记录
            return;
        }
        // 操作人跟随触发方：用户自己取消记小程序、商家拒单记后台、超时自动记系统，
        // 与「谁让这笔钱变成待退」的事实一致
        statusLogService.record(orderId, OrderLogActions.PAY_REFUNDING,
                pay, PayStatus.REFUNDING, null, LocalDateTime.now());
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
    public boolean releaseForTimeout(Long orderId) {
        Order order = require(orderId);
        // 口径来自 OrderQuery：定时任务的扫描 SQL 与这里引同一组常量，改一处就是改两处
        if (!OrderQuery.prepayReleasable(order.getStatus(), order.getPayStatus())
                || Boolean.TRUE.equals(order.getStockReleased())) {
            return false;
        }
        // 条件更新：pay_status 也写进 WHERE，避免「查的时候没付款、更新的时候已声称」
        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStockReleased, true)
                .set(Order::getStockReleasedAt, now)
                .eq(Order::getId, orderId)
                .in(Order::getStatus, OrderQuery.PREPAY_RELEASABLE_STATUSES)
                .in(Order::getPayStatus, OrderQuery.PREPAY_RELEASABLE_PAY_STATUSES)
                .eq(Order::getStockReleased, false));
        if (affected == 0) {
            return false;
        }
        // 上面那条 UPDATE 已经把 stock_released 置 true 了，这里不能再走 releaseStock
        // （抢占会失败，库存永远减不回去），直接减库存
        doReleaseStock(order);
        // 订单状态没变（仍在待接单），但份数被系统收走了 —— 不记的话商家会以为份数凭空少了一份
        statusLogService.record(orderId, OrderLogActions.EVENT_STOCK_RELEASE, null, null,
                "超时未付款，份数已释放", AuditHelper.SYSTEM, AuditHelper.SOURCE_TASK, now);
        log.info("超时未付款释放份数 orderNo={} serveDate={}", order.getOrderNo(), order.getServeDate());
        // 份数被收走了必须让用户知道：订单还挂着待接单，他却不知道那份可能已经被别人买走。
        // 只落站内消息、不发订阅消息（见 NotifyService.MESSAGE_ONLY）
        notifyService.stockReleased(order);
        return true;
    }

    /* ==================== 内部 ==================== */

    Order require(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.notFound("订单不存在");
        }
        return order;
    }

    private Order requireOwned(Long orderId, Long userId) {
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

    /**
     * 自取单快照：联系人固定为昵称，电话选填（可能是 null），详细地址与区域留空。
     * 快照一经写入不再随账号手机号变化，与配送单同一原则。
     */
    static AddressSnapshot pickupSnapshot(User user) {
        if (user == null) {
            return null;
        }
        return new AddressSnapshot(user.getNickName(), trimToNull(user.getPhone()), null, null, null);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 出参里的联系人电话一律前 3 后 2。
     *
     * <p>库里的快照保持明文（履约要真拨），但接口响应不返回 11 位连续号：
     * 抓一次包就能拿到整批用户号码，这才是监管与黑产真正盯的入口。
     * 商家要真拨号，走 P1-20 的「联系顾客」接口（有权限、有频控、有记录），别把明文塞回列表。
     */
    private static AddressSnapshot maskedAddress(AddressSnapshot address) {
        if (address == null) {
            return null;
        }
        return new AddressSnapshot(address.contactName(), MaskUtil.phoneShort(address.phone()),
                address.detail(), address.regionName(), address.tag());
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 用户填的自由文本先过内容安全。
     *
     * <p>只有命中违规才拒（87014），检测接口不可用时放行——熟人小店的场景里，
     * 把正常用户挡在门外比漏掉一句话更糟。备注会进配送单与备菜表，传播面比昵称大。
     */
    private void requireContentOk(Long userId, String content, String message) {
        if (content == null) {
            return;
        }
        User user = userMapper.selectById(userId);
        String openid = user == null ? null : user.getOpenid();
        if (!contentSecurity.pass(userId, openid, content)) {
            throw BizException.param(message);
        }
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
                order.getPayMethod(),
                // 字典优先（商家改了显示名要跟着变）；字典项被删则落到兜底：
                // 内置三项给中文，商家自建项原样显示存值（需求：字典没了显示原 code）
                dictService.label(DictCodes.PAY_METHOD, order.getPayMethod(),
                        PayMethod.label(order.getPayMethod())),
                order.getItems(), order.getGoodsAmount(), order.getSpecAmount(),
                order.getDeliveryFee(), order.getTotalAmount(),
                order.getPickupCode(), order.getPickupSlot(),
                order.getRegionSnapshot(), maskedAddress(order.getAddressSnapshot()), order.getRemark(),
                Boolean.TRUE.equals(order.getCancelRequested()), order.getCancelRequest(),
                order.getRejectReason(), Boolean.TRUE.equals(order.getStockReleased()),
                order.getCreatedAt(), order.getCancelRequestedAt(),
                // 是否已过截单：两端据此决定按钮叫「取消订单」还是「申请取消」。
                // 由服务端算，前端不拿 serveDate + 配置自己拼——两处算法一旦不一致，
                // 就会出现「按钮说能直接取消、提交却变成申请」。
                order.getServeDate() != null && clock.isCutoff(order.getServeDate()),
                // 实际退款额与备注（v1.53 / F-F06）：为空表示还没退或 v3.49 之前的历史单，
                // 前端在「已退款」行上展示，其它状态不渲染
                order.getRefundAmount(), order.getRefundRemark());
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
    public record CreateCommand(String serveDate, String deliveryType, Long addressId,
                                String pickupSlot, String remark,
                                List<ItemInput> items, String requestId) {
    }

    /**
     * @param optionIds 所选规格选项 ID。**不传组 ID**：一个选项必属一组，
     *                  由服务端按快照反向定位所属组，前端少一层映射就少一处出错
     */
    public record ItemInput(Long comboId, Integer qty, List<String> optionIds) {
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
    public record OrderView(Long orderId, String orderNo, LocalDate serveDate, String deliveryType,
                            String status, String statusLabel, String statusTag,
                            String payStatus, String payStatusLabel, String payStatusTag,
                            String payMethod, String payMethodLabel,
                            List<OrderItem> items, Integer goodsAmount, Integer specAmount,
                            Integer deliveryFee, Integer totalAmount,
                            String pickupCode, String pickupSlot,
                            RegionSnapshot region, AddressSnapshot address, String remark,
                            boolean cancelRequested, CancelRequest cancelRequest,
                            String rejectReason, boolean stockReleased,
                            LocalDateTime createdAt, LocalDateTime cancelRequestedAt,
                            /** 该供餐日是否已过截单：决定用户端按钮是「取消」还是「申请取消」 */
                            boolean cutoff,
                            /** 实际退款额（分）与退款备注：只在「已退款」时有值 */
                            Integer refundAmount, String refundRemark) {
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

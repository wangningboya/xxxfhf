package com.xxxfhf.meal.print.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.goods.domain.ComboDish;
import com.xxxfhf.meal.order.domain.AddressSnapshot;
import com.xxxfhf.meal.order.domain.Order;
import com.xxxfhf.meal.order.domain.OrderItem;
import com.xxxfhf.meal.order.domain.OrderStatus;
import com.xxxfhf.meal.order.domain.PayStatus;
import com.xxxfhf.meal.order.domain.RegionSnapshot;
import com.xxxfhf.meal.order.mapper.OrderMapper;
import com.xxxfhf.meal.print.domain.OrderPrintPage;
import com.xxxfhf.meal.print.mapper.OrderPrintPageMapper;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 标签打印的业务侧：出标签数据、记账、给列表补「打过几张」。
 *
 * <p><b>排版相关的一切都在端上</b>，这里只负责「内容」——按模板裁字段、脱敏、截断、拼文案。
 * 分界很清楚：改版式不用发后端，改文案口径不用动端上。
 *
 * <p>三个刻意的决定，都写进代码注释了：
 * <ul>
 *   <li>展开到张：一单 A×2 在这里就是两条，端上拿到即队列，不再做「页 × 份数」的二次展开；</li>
 *   <li>按模板裁字段：自取单不返回地址、配送单不返回时段，从数据层杜绝端上画错；</li>
 *   <li>禁打裁决在服务端：端上置灰只是体验，取消/退款单真被选中时这里整单跳过并说明原因。</li>
 * </ul>
 */
@Service
public class PrintService {

    private static final Logger log = LoggerFactory.getLogger(PrintService.class);

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter TIME_TEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 标题行能放下的半角单位数：9 个汉字 + 1 个空格 + `11-25` 正好占满 45.5mm（需求 3.4） */
    private static final int COMBO_NAME_UNITS = 18;
    /** 小菜+规格一行 19 个汉字（45.5mm ÷ 2.3mm）。v0.8 起按单行留框，不再当两行的第一行用 */
    private static final int DISH_SPEC_UNITS = 38;
    /** 区域一行，12 个汉字 */
    private static final int REGION_UNITS = 24;
    /** 地址两行、每行 18 个汉字（45.5mm ÷ 2.5mm）：上限是两行的总量，不是一行的容量 */
    private static final int ADDRESS_UNITS = 72;
    /** 备注一行，27 个汉字 */
    private static final int REMARK_UNITS = 54;

    private final OrderMapper orderMapper;
    private final OrderPrintPageMapper printMapper;
    private final UserMapper userMapper;
    private final QrCodeService qrCodeService;

    public PrintService(OrderMapper orderMapper, OrderPrintPageMapper printMapper,
                        UserMapper userMapper, QrCodeService qrCodeService) {
        this.orderMapper = orderMapper;
        this.printMapper = printMapper;
        this.userMapper = userMapper;
        this.qrCodeService = qrCodeService;
    }

    /* ==================== 出标签数据 ==================== */

    /**
     * 按订单出标签，**已展开到张**。
     *
     * <p>保持入参顺序：批量打印的先后由商家勾选决定，按 ID 排序会让结果页的列表与
     * 他勾选的顺序对不上，中断后对着名单找不着是哪一张。
     */
    public LabelsResult labels(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw BizException.param("请选择要打印的订单");
        }
        // 一次批量最多 100 单：再多也不该在一批里打，截断而不是报错（前端还有 30 张的上限）
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().limit(100).toList();
        Map<Long, Order> orderMap = new HashMap<>();
        for (Order order : orderMapper.selectBatchIds(ids)) {
            orderMap.put(order.getId(), order);
        }
        Map<Long, User> userMap = usersOf(orderMap.values());
        Map<Long, PrintStat> stats = statsOf(ids);

        List<LabelPage> pages = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (Long id : ids) {
            Order order = orderMap.get(id);
            if (order == null) {
                skipped.add(new Skipped(id, "notFound"));
                continue;
            }
            String blocked = blockedReason(order);
            if (blocked != null) {
                skipped.add(new Skipped(id, blocked));
                continue;
            }
            List<OrderItem> items = order.getItems() == null ? List.of() : order.getItems();
            if (items.isEmpty()) {
                // 正常下单不可能出现；真出现了就别打一张只有取餐码的空标签
                skipped.add(new Skipped(id, "noItems"));
                continue;
            }

            String template = OrderStatus.TYPE_DELIVERY.equals(order.getDeliveryType()) ? "delivery" : "pickup";
            String qrPath = qrCodeService.cachedPath(order.getOrderNo());
            if (qrPath.isEmpty()) {
                // 打印时不现场生成（会卡 3~5 秒），但顺手投一次后台任务，下次打就有码了
                qrCodeService.generateAsync(order.getOrderNo());
            }

            AddressSnapshot address = order.getAddressSnapshot();
            RegionSnapshot region = order.getRegionSnapshot();
            User user = userMap.get(order.getUserId());

            for (int i = 0; i < items.size(); i++) {
                OrderItem item = items.get(i);
                int comboIndex = i + 1;
                int qty = Math.max(item.qtyOrZero(), 0);
                for (int copy = 1; copy <= qty; copy++) {
                    pages.add(new LabelPage(
                            "o" + order.getId() + "-i" + comboIndex + "-c" + copy,
                            template,
                            order.getId(),
                            order.getOrderNo(),
                            comboIndex,
                            truncate(item.name(), COMBO_NAME_UNITS),
                            copy,
                            qty,
                            dishSpecText(item),
                            order.getServeDate() == null ? "" : order.getServeDate().format(MONTH_DAY),
                            contactName(address, user),
                            phoneTail(address == null ? null : address.phone()),
                            // 自取单不打区域与地址：餐盒就在店里，这两行对经手人是零信息量
                            "delivery".equals(template) && region != null ? truncate(region.regionName(), REGION_UNITS) : null,
                            "delivery".equals(template) && address != null ? truncate(address.detail(), ADDRESS_UNITS) : null,
                            // 配送单的 pickupSlot 在库里恒为 null，画上去只会是个空格子
                            "pickup".equals(template) ? order.getPickupSlot() : null,
                            truncate(order.getRemark(), REMARK_UNITS),
                            // 配送单不打取餐码：送到门口喊号没人应，端上右栏只留二维码与份数
                            "delivery".equals(template) ? null : order.getPickupCode(),
                            qrPath.isEmpty() ? "" : qrPath
                    ));
                }
            }
        }

        Map<Long, PrintedStat> printed = new LinkedHashMap<>();
        stats.forEach((orderId, stat) -> {
            Order order = orderMap.get(orderId);
            printed.put(orderId, new PrintedStat(stat.printed(), stat.total(), stat.lastAt()));
        });
        return new LabelsResult(pages, skipped, pages.size(), printed);
    }

    /**
     * 禁打裁决（需求 7.3 第 5 条）。
     *
     * <p>打出作废标签贴在饭盒上就是实打实的损耗，所以这条既要前端置灰、也要服务端复核。
     * 命中时**整单跳过**并在响应里说明原因，不静默出纸、也不让整批失败。
     *
     * @return 原因码；null 表示可以打
     */
    private static String blockedReason(Order order) {
        if (OrderStatus.CANCELLED.equals(order.getStatus())) {
            return "cancelled";
        }
        if (PayStatus.REFUNDED.equals(order.getPayStatus())) {
            return "refunded";
        }
        if (PayStatus.REFUNDING.equals(order.getPayStatus())) {
            return "refunding";
        }
        if (Boolean.TRUE.equals(order.getCancelRequested())) {
            // 用户已申请取消、还没批：这单大概率不做，打了就是废纸
            return "refundRequested";
        }
        if (OrderStatus.TYPE_DELIVERY.equals(order.getDeliveryType())) {
            AddressSnapshot address = order.getAddressSnapshot();
            if (address == null || address.detail() == null || address.detail().isBlank()) {
                // 配送单没有地址就是送不到，打一张送不到的标签没有意义
                return "addressMissing";
            }
        }
        return null;
    }

    /* ==================== 记账 ==================== */

    /**
     * 记一张纸的结果。一张一报，成功失败都报。
     *
     * <p>幂等靠 {@code (order_id, page_key)} 唯一键实现，但**不是简单覆盖**：
     * 同一张先失败、重打成功后要升级成 success（否则列表永远标「部分已打」）；
     * 已经成功的再补打一次不覆盖时间与操作人（那是补打，不是新的一张纸）。
     */
    @Transactional
    public void recordPage(Long operatorId, ReportCommand command) {
        if (command.orderId() == null || command.pageKey() == null || command.pageKey().isBlank()) {
            throw BizException.param("缺少订单或页标识");
        }
        String result = "success".equals(command.result()) ? "success" : "fail";
        Order order = orderMapper.selectById(command.orderId());
        String operatorName = nameOf(operatorId);

        OrderPrintPage existing = printMapper.selectOne(Wrappers.<OrderPrintPage>lambdaQuery()
                .eq(OrderPrintPage::getOrderId, command.orderId())
                .eq(OrderPrintPage::getPageKey, command.pageKey()));

        if (existing != null) {
            if ("success".equals(existing.getResult()) || "fail".equals(result)) {
                // 已成功的不降级；失败重复上报也不必重写（同一张不会因为多报一次就多失败一次）
                return;
            }
            existing.setResult("success");
            existing.setErrCode(null);
            existing.setOperatorId(operatorId);
            existing.setOperatorName(operatorName);
            existing.setDeviceName(command.deviceName());
            existing.setBatchNo(command.batchNo());
            existing.setCreatedAt(LocalDateTime.now());
            printMapper.updateById(existing);
            return;
        }

        OrderPrintPage row = new OrderPrintPage();
        row.setBatchNo(command.batchNo() == null || command.batchNo().isBlank()
                ? String.valueOf(System.currentTimeMillis()) : command.batchNo());
        row.setOrderId(command.orderId());
        row.setPageKey(command.pageKey());
        row.setServeDate(order == null ? null : order.getServeDate());
        row.setTemplate("pickup".equals(command.template()) ? "pickup" : "delivery");
        row.setResult(result);
        row.setErrCode("fail".equals(result) ? command.errCode() : null);
        row.setOperatorId(operatorId);
        row.setOperatorName(operatorName);
        row.setDeviceName(command.deviceName());
        row.setCreatedAt(LocalDateTime.now());
        printMapper.insert(row);
    }

    /**
     * 批量取每单的打印进度。
     *
     * @return orderId → {已打张数, 应打张数, 最近一次成功时间}；没打过的单不在返回值里
     */
    public Map<Long, PrintStat> statsOf(List<Long> orderIds) {
        Map<Long, PrintStat> result = new HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = printMapper.statsByOrderIds(orderIds);
        Map<Long, Order> orderMap = new HashMap<>();
        for (Order order : orderMapper.selectBatchIds(orderIds)) {
            orderMap.put(order.getId(), order);
        }
        for (Map<String, Object> row : rows) {
            Long orderId = asLong(row.get("orderId"));
            if (orderId == null) {
                continue;
            }
            Order order = orderMap.get(orderId);
            int total = totalCopies(order);
            int printed = asInt(row.get("printed"));
            result.put(orderId, new PrintStat(printed, total, timeText(row.get("lastAt"))));
        }
        return result;
    }

    /** 某单的打印记录（按批聚合，最近 10 批），详情页用 */
    public List<PrintRecordRow> recordsOf(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = printMapper.recentBatches(orderId);
        List<PrintRecordRow> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            int copies = asInt(row.get("copies"));
            int successCopies = asInt(row.get("successCopies"));
            Object failCode = row.get("failErrCode");
            result.add(new PrintRecordRow(
                    String.valueOf(row.get("batchNo")),
                    timeText(row.get("createdAt")),
                    row.get("operatorName") == null ? "" : String.valueOf(row.get("operatorName")),
                    row.get("deviceName") == null ? "" : String.valueOf(row.get("deviceName")),
                    copies,
                    successCopies,
                    // 全成功 = success，有一张失败 = fail，前端据此上色
                    successCopies >= copies ? "success" : "fail",
                    failCode == null ? null : asInt(failCode)
            ));
        }
        return result;
    }

    /** 应打几张 = 明细份数之和（页 = 张） */
    public static int totalCopies(Order order) {
        if (order == null || order.getItems() == null) {
            return 0;
        }
        int total = 0;
        for (OrderItem item : order.getItems()) {
            total += Math.max(item.qtyOrZero(), 0);
        }
        return total;
    }

    /**
     * 三态角标（需求 6.2）。
     *
     * <p>按**张**判定而不是按订单：A×2 只出了 1 张时标「已打」会让商家以为齐了，
     * 而打包台少一张就是少一份饭。
     */
    public static String stateOf(int printed, int total) {
        if (printed <= 0) {
            return "none";
        }
        return printed >= total ? "done" : "partial";
    }

    /* ==================== 内部 ==================== */

    private Map<Long, User> usersOf(java.util.Collection<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = orders.stream().map(Order::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, User> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            map.put(user.getId(), user);
        }
        return map;
    }

    private String nameOf(Long operatorId) {
        if (operatorId == null) {
            return null;
        }
        User user = userMapper.selectById(operatorId);
        if (user == null) {
            return null;
        }
        return user.getNickName() == null || user.getNickName().isBlank()
                ? user.getUsername() : user.getNickName();
    }

    /** 联系人：收货人 → 用户昵称 → 「顾客」。缺失也要有人，否则标签上写不出「谁的单」 */
    private static String contactName(AddressSnapshot address, User user) {
        if (address != null && address.contactName() != null && !address.contactName().isBlank()) {
            return truncate(address.contactName(), 16);
        }
        if (user != null && user.getNickName() != null && !user.getNickName().isBlank()) {
            return truncate(user.getNickName(), 16);
        }
        return "顾客";
    }

    /**
     * 电话后四位。**永不打全号**：打全号等于把顾客号码交给每一个经手餐盒的人。
     *
     * @return 4 位数字；没留电话或号码不足 4 位返回空串（端上显示「未留电话」）
     */
    private static String phoneTail(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : "";
    }

    /** 小菜 + 规格拼成一段：`青菜 · 卤蛋 · 例汤｜微辣`（需求 4.2） */
    private static String dishSpecText(OrderItem item) {
        StringBuilder dishes = new StringBuilder();
        if (item.dishes() != null) {
            for (ComboDish dish : item.dishes()) {
                if (dish.name() == null || dish.name().isBlank()) {
                    continue;
                }
                if (dishes.length() > 0) {
                    dishes.append(" · ");
                }
                dishes.append(dish.name());
                int qty = dish.qtyOrDefault();
                if (qty > 1) {
                    dishes.append("×").append(qty);
                }
            }
        }
        StringBuilder specs = new StringBuilder();
        if (item.specs() != null) {
            for (OrderItem.Spec spec : item.specs()) {
                if (spec.optionName() == null || spec.optionName().isBlank()) {
                    continue;
                }
                if (specs.length() > 0) {
                    specs.append(" · ");
                }
                specs.append(spec.optionName());
            }
        }
        if (dishes.length() == 0 && specs.length() == 0) {
            return null;
        }
        if (specs.length() == 0) {
            return truncate(dishes.toString(), DISH_SPEC_UNITS);
        }
        if (dishes.length() == 0) {
            return truncate(specs.toString(), DISH_SPEC_UNITS);
        }
        return truncate(dishes + "｜" + specs, DISH_SPEC_UNITS);
    }

    /**
     * 按**半角单位**截断，中文算 2。
     *
     * <p>为什么不在端上截：屏幕和纸面是两套宽度口径，只在服务端算一次，
     * 以后接云打印、做后台预览都还是同一个结果。
     *
     * <p>先量一遍再决定截不截：省略号占 2 个单位，但那 2 个只有真要截的时候才该扣。
     * 无条件预扣会让「刚好等于上限」的文本平白少一个字 ——
     * 9 个汉字的套餐名会被砍成 8 字加「…」，而 4.4 的上限正是 9 字。
     */
    static String truncate(String text, int maxUnits) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (unitsOf(value) <= maxUnits) {
            return value;
        }
        int budget = Math.max(maxUnits - 2, 2);
        int units = 0;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int width = widthOf(codePoint);
            if (units + width > budget) {
                return builder.append('…').toString();
            }
            builder.appendCodePoint(codePoint);
            units += width;
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    /** 字符串占多少半角单位 */
    private static int unitsOf(String value) {
        int units = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            units += widthOf(codePoint);
            i += Character.charCount(codePoint);
        }
        return units;
    }

    private static int widthOf(int codePoint) {
        return codePoint < 0x100 ? 1 : 2;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 时间显示到分钟：同一分钟内打的两批在结果页没必要区分 */
    private static String timeText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime time) {
            return time.format(TIME_TEXT);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(TIME_TEXT);
        }
        String raw = String.valueOf(value);
        // MyBatis 返回 Map 时可能是 "2026-11-24 18:30:00.0"，截到分钟即可
        return raw.length() >= 16 ? raw.substring(5, 16) : raw;
    }

    /* ==================== 对外结构 ==================== */

    /**
     * 一张纸的标签数据。
     *
     * <p>字段按模板裁剪：自取单的 {@code regionName} / {@code addressText} 恒为 null，
     * 配送单的 {@code pickupSlot} 恒为 null —— 从数据层杜绝端上画错字段。
     */
    public record LabelPage(String pageKey, String template, Long orderId, String orderNo,
                            Integer comboIndex, String comboName, Integer copyIndex, Integer copyTotal,
                            String dishSpecText, String serveDate, String contactName, String phoneTail,
                            String regionName, String addressText, String pickupSlot, String remark,
                            String pickupCode, String qrImageUrl) {
    }

    /** @param reason cancelled / refunded / refunding / refundRequested / addressMissing / noItems / notFound */
    public record Skipped(Long orderId, String reason) {
    }

    /** @param printedPages 已打张数；@param totalPages 应打张数（字段名沿用 Pages，口径是张） */
    public record PrintedStat(int printedPages, int totalPages, String lastAt) {
    }

    public record LabelsResult(List<LabelPage> pages, List<Skipped> skipped, int totalCount,
                               Map<Long, PrintedStat> printed) {
    }

    /** @param result success / fail；@param failErrCode 第一条失败的错误码 */
    public record PrintRecordRow(String batchNo, String createdAt, String operatorName,
                                 String deviceName, int copies, int successCopies,
                                 String result, Integer failErrCode) {
    }

    public record PrintStat(int printed, int total, String lastAt) {
    }

    /** 端上上报一张打印结果 */
    public record ReportCommand(String batchNo, Long orderId, String pageKey, String template,
                                String result, Integer errCode, String deviceName) {
    }
}

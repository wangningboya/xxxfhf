package com.xxxfhf.meal.prep;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.goods.ComboDish;
import com.xxxfhf.meal.menu.ServeDateClock;
import com.xxxfhf.meal.order.AddressSnapshot;
import com.xxxfhf.meal.order.Order;
import com.xxxfhf.meal.order.OrderItem;
import com.xxxfhf.meal.order.OrderMapper;
import com.xxxfhf.meal.order.OrderStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 备菜统计（F-G03 ~ G06）。
 *
 * 口径三条，全部来自需求 4.14：
 * 1. **只算已接单及之后**（accepted / ready / completed），待接单不计 ——
 *    商家接完单刷新备菜页数字才会变，这是刻意的
 * 2. **全部从订单快照算**，不 join 实时盒饭 / 菜品 / 地址表；不落统计表，每次实时算
 * 3. **规格项只算标记了「计入备菜」的组**，按选项名分别累加（白米饭 12、杂粮饭 8），
 *    不聚合到「主食」这一层
 */
@Service
public class PrepService {

    private final OrderMapper orderMapper;
    private final ServeDateClock clock;

    public PrepService(OrderMapper orderMapper, ServeDateClock clock) {
        this.orderMapper = orderMapper;
        this.clock = clock;
    }

    /**
     * 备菜视图。serveDate 留空按「最近的待供餐日」（明天）。
     */
    public PrepView view(LocalDate serveDate) {
        LocalDate date = serveDate == null ? clock.tomorrow() : serveDate;
        List<Order> orders = ordersOf(date);

        Map<String, DishAgg> dishMap = new LinkedHashMap<>();
        Map<String, RegionAgg> regionMap = new LinkedHashMap<>();
        RegionAgg selfPickup = new RegionAgg(null, "自取（不分区域）");

        for (Order order : orders) {
            List<OrderItem> items = order.getItems() == null ? List.of() : order.getItems();
            boolean pickup = isPickup(order);
            RegionAgg region = pickup ? selfPickup
                    : regionMap.computeIfAbsent(order.getRegionId(),
                    id -> new RegionAgg(id, regionNameOf(order)));

            region.orderCount++;
            region.addContact(order.getAddressSnapshot());

            for (OrderItem item : items) {
                int qty = item.qtyOrZero();
                if (qty <= 0) {
                    continue;
                }
                region.addCombo(item.name(), qty);
                if (pickup) {
                    region.addSlot(order.getPickupSlot(), qty);
                }
                accumulateDishes(dishMap, item, qty);
            }
        }

        List<DishRow> dishes = new ArrayList<>(dishMap.size());
        for (DishAgg agg : dishMap.values()) {
            dishes.add(agg.toRow());
        }

        List<RegionRow> regions = new ArrayList<>(regionMap.size());
        for (RegionAgg agg : regionMap.values()) {
            regions.add(agg.toRow());
        }
        // 自取固定排最后一行：它不属于任何区域，混在中间会让人以为是某个小区
        return new PrepView(date, dishes, regions, selfPickup.toRow(), orders.size());
    }

    /**
     * 导出两个 Sheet：菜品汇总、区域汇总。自取固定最后一行。
     *
     * 单日 300 单的量级，一次全量导出对内存没有压力；不搞分页写入，
     * 多一层分页就多一处「导出的数和页面上的数不一样」的可能。
     */
    public byte[] export(LocalDate serveDate) {
        PrepView view = view(serveDate);

        List<DishExcelRow> dishRows = new ArrayList<>();
        for (DishRow row : view.dishes()) {
            dishRows.add(new DishExcelRow(
                    "dish".equals(row.kind()) ? "菜品" : "规格",
                    row.name(), row.groupName(), row.qty(), String.join("、", row.comboNames())));
        }

        List<RegionExcelRow> regionRows = new ArrayList<>();
        for (RegionRow row : view.regions()) {
            regionRows.add(toRegionExcel(row));
        }
        regionRows.add(toRegionExcel(view.selfPickup()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            WriteSheet dishSheet = EasyExcel.writerSheet(0, "菜品汇总")
                    .head(DishExcelRow.class).build();
            writer.write(dishRows, dishSheet);
            WriteSheet regionSheet = EasyExcel.writerSheet(1, "区域汇总")
                    .head(RegionExcelRow.class).build();
            writer.write(regionRows, regionSheet);
        }
        return out.toByteArray();
    }

    /* ==================== 内部 ==================== */

    private List<Order> ordersOf(LocalDate serveDate) {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getServeDate, serveDate)
                .in(Order::getStatus, OrderStatus.PREP_INCLUDED));
    }

    /** 自取单没有区域；区域为空的配送单（历史脏数据）也归到自取栏，绝不丢弃。 */
    private static boolean isPickup(Order order) {
        return "pickup".equals(order.getDeliveryType()) || order.getRegionId() == null;
    }

    private static String regionNameOf(Order order) {
        return order.getRegionSnapshot() == null || order.getRegionSnapshot().regionName() == null
                ? "未知区域" : order.getRegionSnapshot().regionName();
    }

    /** 菜品与规格进同一张表，用 kind 区分；规格按「每份盒饭 1 份」累加。 */
    private static void accumulateDishes(Map<String, DishAgg> dishMap, OrderItem item, int qty) {
        if (item.dishes() != null) {
            for (ComboDish dish : item.dishes()) {
                DishAgg agg = dishMap.computeIfAbsent(dish.dishId(),
                        id -> new DishAgg(dish.dishId(), dish.name(), "dish", null));
                agg.qty += dish.qtyOrDefault() * qty;
                agg.combos.add(item.name());
            }
        }
        if (item.specs() != null) {
            for (OrderItem.Spec spec : item.specs()) {
                if (!Boolean.TRUE.equals(spec.countInPrep())) {
                    // 辣度这类非物料规格不进备菜表，否则备菜清单会混进一堆「微辣 8 份」
                    continue;
                }
                String key = "spec:" + spec.optionName();
                DishAgg agg = dishMap.computeIfAbsent(key,
                        k -> new DishAgg(k, spec.optionName(), "spec", spec.groupName()));
                agg.qty += qty;
                agg.combos.add(item.name());
            }
        }
    }

    private static RegionExcelRow toRegionExcel(RegionRow row) {
        String combos = row.items().stream()
                .map(c -> c.name() + " ×" + c.qty())
                .reduce((a, b) -> a + "、" + b).orElse("");
        String contacts = row.contacts().stream()
                .map(c -> cell(c.contactName()) + (c.detail() == null ? "" : "（" + cell(c.detail()) + "）"))
                .reduce((a, b) -> a + "；" + b).orElse("");
        String slots = row.slots().stream()
                .map(s -> (s.slot() == null ? "未填时段" : s.slot()) + "：" + s.qty() + " 份")
                .reduce((a, b) -> a + "、" + b).orElse("");
        return new RegionExcelRow(row.regionName(), row.qty(), row.orderCount(), combos, contacts, slots);
    }

    /**
     * 导出单元格防公式注入。
     *
     * <p>收货人姓名和详细地址是用户自己填的：开头一个 {@code =} 就能让 Excel、
     * WPS、Numbers 在商家打开表格时执行公式（=HYPERLINK 外发数据是常见玩法）。
     * 加单引号后 Excel 按文本处理，显示时看不到这个引号。
     */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    /* ==================== 聚合中间态 ==================== */

    private static final class DishAgg {
        private final String dishId;
        private final String name;
        private final String kind;
        private final String groupName;
        private int qty;
        private final Set<String> combos = new LinkedHashSet<>();

        private DishAgg(String dishId, String name, String kind, String groupName) {
            this.dishId = dishId;
            this.name = name;
            this.kind = kind;
            this.groupName = groupName;
        }

        private DishRow toRow() {
            return new DishRow(dishId, name, kind, groupName, qty, new ArrayList<>(combos));
        }
    }

    private static final class RegionAgg {
        private final String regionId;
        private final String regionName;
        private int qty;
        private int orderCount;
        private final Map<String, Integer> combos = new LinkedHashMap<>();
        private final List<ContactRow> contacts = new ArrayList<>();
        private final Map<String, Integer> slots = new LinkedHashMap<>();

        private RegionAgg(String regionId, String regionName) {
            this.regionId = regionId;
            this.regionName = regionName;
        }

        private void addCombo(String name, int itemQty) {
            qty += itemQty;
            combos.merge(name, itemQty, Integer::sum);
        }

        private void addSlot(String slot, int itemQty) {
            slots.merge(slot == null ? "未填时段" : slot, itemQty, Integer::sum);
        }

        private void addContact(AddressSnapshot address) {
            if (address == null) {
                return;
            }
            // 备菜页只留「谁、送到哪」：菜要做给这个人，电话对分拣没有用。
            // 这一页最快到人最少，一旦把整批号码铺在上面，等于给每个能进后台的人发了一份通讯录
            contacts.add(new ContactRow(address.contactName(), null, address.detail()));
        }

        private RegionRow toRow() {
            List<ComboQty> items = new ArrayList<>(combos.size());
            combos.forEach((name, q) -> items.add(new ComboQty(name, q)));
            List<SlotRow> slotRows = new ArrayList<>(slots.size());
            slots.forEach((slot, q) -> slotRows.add(new SlotRow(slot, q)));
            return new RegionRow(regionId, regionName, qty, items, orderCount,
                    new ArrayList<>(contacts), slotRows);
        }
    }

    /* ==================== 视图 ==================== */

    /**
     * @param kinds dish = 菜品，spec = 规格选项
     * @param groupName 规格选项所属组名；菜品为 null
     */
    public record DishRow(String dishId, String name, String kind, String groupName,
                          int qty, List<String> comboNames) {
    }

    public record ComboQty(String name, int qty) {
    }

    public record ContactRow(String contactName, String phone, String detail) {
    }

    public record SlotRow(String slot, int qty) {
    }

    /**
     * @param slots 仅自取栏有值（按半小时时段分组，便于安排出餐节奏）
     */
    public record RegionRow(String regionId, String regionName, int qty, List<ComboQty> items,
                            int orderCount, List<ContactRow> contacts, List<SlotRow> slots) {
    }

    /**
     * @param orderCount 计入备菜的订单数（已接单及之后，不含待接单）
     * @param selfPickup 自取栏，固定排在区域汇总最后一行
     */
    public record PrepView(LocalDate serveDate, List<DishRow> dishes, List<RegionRow> regions,
                           RegionRow selfPickup, int orderCount) {
    }

    /* ==================== Excel 行 ==================== */

    @ColumnWidth(12)
    public static class DishExcelRow {

        @ExcelProperty("类型")
        private String kind;
        @ExcelProperty("名称")
        @ColumnWidth(20)
        private String name;
        @ExcelProperty("所属组")
        private String groupName;
        @ExcelProperty("需要份数")
        private int qty;
        @ExcelProperty("涉及盒饭")
        @ColumnWidth(36)
        private String combos;

        public DishExcelRow() {
        }

        public DishExcelRow(String kind, String name, String groupName, int qty, String combos) {
            this.kind = kind;
            this.name = name;
            this.groupName = groupName;
            this.qty = qty;
            this.combos = combos;
        }

        public String getKind() {
            return kind;
        }

        public String getName() {
            return name;
        }

        public String getGroupName() {
            return groupName;
        }

        public int getQty() {
            return qty;
        }

        public String getCombos() {
            return combos;
        }
    }

    @ColumnWidth(16)
    public static class RegionExcelRow {

        @ExcelProperty("区域")
        private String regionName;
        @ExcelProperty("盒饭份数")
        private int qty;
        @ExcelProperty("订单数")
        private int orderCount;
        @ExcelProperty("盒饭明细")
        @ColumnWidth(30)
        private String combos;
        @ExcelProperty("收货人")
        @ColumnWidth(40)
        private String contacts;
        @ExcelProperty("取餐时段")
        @ColumnWidth(30)
        private String slots;

        public RegionExcelRow() {
        }

        public RegionExcelRow(String regionName, int qty, int orderCount,
                              String combos, String contacts, String slots) {
            this.regionName = regionName;
            this.qty = qty;
            this.orderCount = orderCount;
            this.combos = combos;
            this.contacts = contacts;
            this.slots = slots;
        }

        public String getRegionName() {
            return regionName;
        }

        public int getQty() {
            return qty;
        }

        public int getOrderCount() {
            return orderCount;
        }

        public String getCombos() {
            return combos;
        }

        public String getContacts() {
            return contacts;
        }

        public String getSlots() {
            return slots;
        }
    }
}

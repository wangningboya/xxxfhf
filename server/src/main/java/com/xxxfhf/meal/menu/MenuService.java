package com.xxxfhf.meal.menu;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.goods.Combo;
import com.xxxfhf.meal.goods.ComboDish;
import com.xxxfhf.meal.goods.ComboService;
import com.xxxfhf.meal.goods.GoodsStatus;
import com.xxxfhf.meal.goods.SpecGroup;
import com.xxxfhf.meal.goods.SpecGroupMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每日菜单：勾选盒饭、设份数、发布、停业。
 *
 * 这个类只负责「菜单长什么样」，**不碰份数占用与订单** —— 占份在下单时由订单域做，
 * 这里是读取方。两者分开，避免「改菜单」和「下单」互相持锁。
 */
@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    private final DailyMenuMapper menuMapper;
    private final ComboService comboService;
    private final SpecGroupMapper specGroupMapper;
    private final ServeDateClock clock;
    private final ConfigService configService;
    /** 菜单变更留痕（需求 4.19 / F-J13）：一处改动会同时影响用户看到的菜单与可售份数 */
    private final AuditService auditService;

    public MenuService(DailyMenuMapper menuMapper, ComboService comboService,
                       SpecGroupMapper specGroupMapper, ServeDateClock clock,
                       ConfigService configService, AuditService auditService) {
        this.menuMapper = menuMapper;
        this.comboService = comboService;
        this.specGroupMapper = specGroupMapper;
        this.clock = clock;
        this.configService = configService;
        this.auditService = auditService;
    }

    /**
     * 菜单没有「一行代表一天」的主键，用供餐日的 {@code yyyyMMdd} 当 {@code record_id}：
     * 操作记录页按对象 ID 搜 {@code 20261124} 就能把这一天的保存 / 发布 / 撤下全捞出来。
     */
    private static long dateKey(LocalDate date) {
        return date.getYear() * 10000L + date.getMonthValue() * 100L + date.getDayOfMonth();
    }

    /* ==================== 用户端 ==================== */

    /**
     * 用户端菜单。serveDate 为空按明天。
     *
     * 只允许明天与后天：更远的日期商家勾了也不给看，否则用户看到的是「下周吃什么」，
     * 与「最多展示两个供餐日」的产品约定冲突。
     */
    public UserMenuView userView(LocalDate serveDate) {
        LocalDate date = serveDate == null ? clock.tomorrow() : serveDate;
        if (!clock.isUserVisible(date)) {
            throw BizException.param("只能查看最近两天的菜单：明天可下单，后天可预览");
        }

        boolean closed = clock.isClosed(date);
        boolean cutoff = clock.isCutoff(date);
        boolean orderable = clock.isOrderable(date);

        Map<Long, DailyMenu> byCombo = publishedMap(date);
        // 排序与可见性都以盒饭库为准：下架的盒饭即使还在菜单里也不展示（下架即彻底隐藏）
        List<MenuItemView> items = new ArrayList<>();
        for (Combo combo : comboService.listOnSale()) {
            DailyMenu menu = byCombo.get(combo.getId());
            if (menu != null) {
                items.add(toItemView(menu));
            }
        }

        return new UserMenuView(date, cutoff, closed, orderable, clock.cutoffAt(date), items);
    }

    /** 全部菜单（盒饭库全量，仅上架的），带「明日供应」标记。只浏览，不提供加购。 */
    public List<ComboAllItem> allCombos() {
        Set<Long> tomorrow = publishedMap(clock.tomorrow()).keySet();
        List<ComboAllItem> result = new ArrayList<>();
        for (Combo combo : comboService.listOnSale()) {
            result.add(new ComboAllItem(combo.getId(), combo.getName(), combo.getCover(), combo.getDesc(),
                    combo.getPrice(), nullToEmpty(combo.getDishes()),
                    snapshotSpecs(combo), tomorrow.contains(combo.getId())));
        }
        return result;
    }

    /* ==================== 商家端 ==================== */

    /** 商家看某日菜单：草稿也返回，否则草稿状态永远看不见。 */
    public AdminMenuView adminView(LocalDate serveDate) {
        LocalDate date = requireDate(serveDate);
        Map<Long, DailyMenu> byCombo = allMap(date);
        List<MenuItemView> items = new ArrayList<>();
        for (Combo combo : comboService.listAll()) {
            DailyMenu menu = byCombo.get(combo.getId());
            if (menu != null) {
                items.add(toItemView(menu));
            }
        }
        // 有任意一条 PUBLISHED 就认为这一天已发布；后台要据此显示「发布 / 撤下」
        boolean published = byCombo.values().stream()
                .anyMatch(menu -> MenuStatus.PUBLISHED.equals(menu.getStatus()));
        return new AdminMenuView(date, clock.isCutoff(date), clock.isClosed(date),
                clock.cutoffAt(date), published, items);
    }

    /**
     * 保存某日菜单（勾选盒饭 + 设份数）。
     *
     * 三个拒绝条件，都对应真实会出错的场景：
     * 1. **非未来日期** —— 今天及过去不能排菜单，用户端本来也不展示
     * 2. **已截单** —— 截单后再改菜单，用户看到的和拿到的就不一样了
     * 3. **限量小于已售** —— 会出现负数剩余，前端算出来的可售份数没意义
     */
    @Transactional
    public void save(LocalDate serveDate, List<MenuInput> items) {
        LocalDate date = requireFutureDate(serveDate);
        if (clock.isCutoff(date)) {
            throw BizException.conflict("该供餐日已截单，不能再调整菜单（用户已按当时的菜单下单）");
        }

        List<MenuInput> inputs = items == null ? List.of() : items;
        Map<Long, DailyMenu> existing = allMap(date);
        Set<Long> keep = new LinkedHashSet<>();

        for (MenuInput input : inputs) {
            Long comboId = input == null ? null : input.comboId();
            if (comboId == null) {
                throw BizException.param("菜单项缺少盒饭 ID");
            }
            if (!keep.add(comboId)) {
                throw BizException.param("同一个盒饭在一份菜单里只能出现一次");
            }
            if (input.limitQty() != null && input.limitQty() < 0) {
                throw BizException.param("限量份数不能为负数");
            }

            Combo combo = comboService.require(comboId);
            if (!GoodsStatus.ON.equals(combo.getStatus())) {
                throw BizException.param("「" + combo.getName() + "」已下架，不能加入每日供应");
            }

            DailyMenu menu = existing.get(comboId);
            if (menu == null) {
                menu = new DailyMenu();
                menu.setServeDate(date);
                menu.setComboId(comboId);
                menu.setSoldQty(0);
                menu.setStatus(MenuStatus.DRAFT);
                applySnapshot(menu, combo, true);
                menu.setLimitQty(input.limitQty());
                menuMapper.insert(menu);
            } else {
                int sold = menu.getSoldQty() == null ? 0 : menu.getSoldQty();
                if (input.limitQty() != null && input.limitQty() < sold) {
                    throw BizException.conflict("「" + combo.getName() + "」已售出 " + sold
                            + " 份，限量不能小于已售份数");
                }
                // 已发布的盒饭**不刷新价格快照**：这是「已发布菜单改价不影响已下单」的落地。
                // 要改价，先把菜单撤下（转 draft），保存后再发布。
                boolean draft = MenuStatus.DRAFT.equals(menu.getStatus());
                applySnapshot(menu, combo, draft);
                menu.setLimitQty(input.limitQty());
                menuMapper.updateById(menu);
            }
        }

        // 本次没勾选的，从菜单里移除；但已有订单占用的不能删，否则订单会指向不存在的菜单行
        for (DailyMenu menu : existing.values()) {
            if (keep.contains(menu.getComboId())) {
                continue;
            }
            int sold = menu.getSoldQty() == null ? 0 : menu.getSoldQty();
            if (sold > 0) {
                throw BizException.conflict("「" + nameOf(menu) + "」已有 " + sold
                        + " 份订单占用，不能从菜单中移除；可把限量改为已售份数，让它自然售罄");
            }
            menuMapper.deleteById(menu.getId());
        }

        log.info("保存每日菜单 serveDate={} 盒饭数={}", date, keep.size());
        auditService.record(AuditActions.TABLE_MENU, dateKey(date), AuditActions.MENU_SAVE,
                "保存 " + date + " 菜单：盒饭 " + keep.size() + " 个");
    }

    /**
     * 发布 / 撤下。
     *
     * 撤下后用户端「后天」Tab 随之消失，不留空 Tab（需求第六章）。
     */
    @Transactional
    public void publish(LocalDate serveDate, boolean published) {
        LocalDate date = requireFutureDate(serveDate);
        if (clock.isCutoff(date)) {
            throw BizException.conflict("该供餐日已截单，不能再发布或撤下菜单");
        }

        List<DailyMenu> menus = menusOf(date);
        if (published && menus.isEmpty()) {
            throw BizException.param("该供餐日还没有勾选任何盒饭，无法发布");
        }
        if (published && clock.isClosed(date)) {
            throw BizException.conflict("该供餐日已设为停业，请先取消停业再发布");
        }

        String target = published ? MenuStatus.PUBLISHED : MenuStatus.DRAFT;
        for (DailyMenu menu : menus) {
            menu.setStatus(target);
            menuMapper.updateById(menu);
        }
        log.info("菜单 {} serveDate={} 盒饭数={}", published ? "发布" : "撤下", date, menus.size());
        auditService.record(AuditActions.TABLE_MENU, dateKey(date),
                published ? AuditActions.MENU_PUBLISH : AuditActions.MENU_CLOSE,
                (published ? "发布 " : "撤下 ") + date + " 菜单：盒饭 " + menus.size() + " 个");
    }

    /** 复制上一份：找出该日期之前最近的一份菜单，返回它的盒饭与限量，供前端预填。 */
    public List<MenuInput> copyFrom(LocalDate serveDate) {
        LocalDate date = requireFutureDate(serveDate);
        List<DailyMenu> all = menuMapper.selectList(Wrappers.<DailyMenu>lambdaQuery()
                .lt(DailyMenu::getServeDate, date)
                .orderByDesc(DailyMenu::getServeDate));
        if (all.isEmpty()) {
            return List.of();
        }
        // 最近的那一天（第一行就是，因为已按日期倒序）
        LocalDate latest = all.get(0).getServeDate();
        List<MenuInput> result = new ArrayList<>();
        for (DailyMenu menu : all) {
            if (!latest.equals(menu.getServeDate())) {
                break;
            }
            result.add(new MenuInput(menu.getComboId(), menu.getLimitQty()));
        }
        return result;
    }

    /**
     * 一键停业 / 取消停业。
     *
     * 作用于**商家指定的供餐日**，截单后也能改 —— 因为「明天不做饭」这件事常常是
     * 截单之后才决定的。已下的订单不受影响，由商家逐个接单或拒单处理。
     */
    @Transactional
    public void setClosed(LocalDate serveDate, boolean closed) {
        LocalDate date = requireDate(serveDate);
        List<String> dates = new ArrayList<>(configService.getStringList(ConfigKey.CLOSED_DATES));
        String value = date.toString();
        if (closed) {
            if (!dates.contains(value)) {
                dates.add(value);
            }
        } else {
            dates.removeIf(value::equals);
        }
        configService.setStringList(ConfigKey.CLOSED_DATES, dates);
        log.info("停业设置 serveDate={} closed={}", date, closed);
    }

    /* ==================== 供订单域使用 ==================== */

    /** 某日全部菜单行（含草稿），订单域占份时需要按 serveDate + comboId 精确定位。 */
    public List<DailyMenu> menusOf(LocalDate serveDate) {
        return menuMapper.selectList(Wrappers.<DailyMenu>lambdaQuery()
                .eq(DailyMenu::getServeDate, serveDate));
    }

    /* ==================== 内部 ==================== */

    private Map<Long, DailyMenu> publishedMap(LocalDate date) {
        List<DailyMenu> list = menuMapper.selectList(Wrappers.<DailyMenu>lambdaQuery()
                .eq(DailyMenu::getServeDate, date)
                .eq(DailyMenu::getStatus, MenuStatus.PUBLISHED));
        Map<Long, DailyMenu> map = new HashMap<>(list.size());
        for (DailyMenu menu : list) {
            map.put(menu.getComboId(), menu);
        }
        return map;
    }

    private Map<Long, DailyMenu> allMap(LocalDate date) {
        Map<Long, DailyMenu> map = new HashMap<>();
        for (DailyMenu menu : menusOf(date)) {
            map.put(menu.getComboId(), menu);
        }
        return map;
    }

    /**
     * 写入快照。
     *
     * @param refresh 是否刷新价格与组成。草稿刷新、已发布不刷新，
     *                这样「已发布菜单沿用价格快照」就不需要额外的锁定字段
     */
    private void applySnapshot(DailyMenu menu, Combo combo, boolean refresh) {
        if (!refresh && menu.getComboSnapshot() != null) {
            return;
        }
        menu.setComboSnapshot(new ComboSnapshot(combo.getName(), combo.getCover(),
                nullToEmpty(combo.getDishes()), snapshotSpecs(combo)));
        menu.setPriceSnapshot(combo.getPrice() == null ? 0 : combo.getPrice());
    }

    /** 把盒饭启用的规格组展开成内容快照（含选项与差价），订单要按当时的差价记录。 */
    private List<ComboSnapshot.SpecGroupSnapshot> snapshotSpecs(Combo combo) {
        List<ComboSnapshot.SpecGroupSnapshot> result = new ArrayList<>();
        if (combo.getSpecGroupIds() == null || combo.getSpecGroupIds().isEmpty()) {
            return result;
        }
        for (Long groupId : combo.getSpecGroupIds()) {
            SpecGroup group = specGroupMapper.selectById(groupId);
            if (group == null || !GoodsStatus.ENABLED.equals(group.getStatus())) {
                // 停用的组不进快照：停用意味着商家已经确认解除引用，这里再带上就是脏数据
                continue;
            }
            result.add(new ComboSnapshot.SpecGroupSnapshot(group.getName(), group.getRequired(),
                    group.getCountInPrep(), group.getOptions() == null ? List.of() : group.getOptions()));
        }
        return result;
    }

    private MenuItemView toItemView(DailyMenu menu) {
        ComboSnapshot snapshot = menu.getComboSnapshot();
        int sold = menu.getSoldQty() == null ? 0 : menu.getSoldQty();
        Integer remain = null;
        if (menu.getLimitQty() != null) {
            remain = Math.max(0, menu.getLimitQty() - sold);
        }
        return new MenuItemView(menu.getComboId(),
                snapshot == null ? nameOf(menu) : snapshot.name(),
                snapshot == null ? null : snapshot.cover(),
                menu.getPriceSnapshot() == null ? 0 : menu.getPriceSnapshot(),
                snapshot == null || snapshot.dishes() == null ? List.of() : snapshot.dishes(),
                snapshot == null || snapshot.specGroups() == null ? List.of() : snapshot.specGroups(),
                menu.getLimitQty(), sold, remain, menu.getStatus());
    }

    private static String nameOf(DailyMenu menu) {
        return menu.getComboSnapshot() == null ? String.valueOf(menu.getComboId()) : menu.getComboSnapshot().name();
    }

    private static List<ComboDish> nullToEmpty(List<ComboDish> dishes) {
        return dishes == null ? List.of() : dishes;
    }

    private LocalDate requireDate(LocalDate serveDate) {
        if (serveDate == null) {
            throw BizException.param("请选择供餐日期");
        }
        return serveDate;
    }

    private LocalDate requireFutureDate(LocalDate serveDate) {
        LocalDate date = requireDate(serveDate);
        if (!date.isAfter(clock.today())) {
            throw BizException.param("只能为未来日期排菜单，今天及过去的日期不可选");
        }
        return date;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /* ==================== 视图 ==================== */

    /**
     * 用户端菜单视图。
     *
     * @param cutoff    是否已截单
     * @param closed    是否停业
     * @param orderable 能否下单（明天 + 未截单 + 未停业）
     * @param cutoffAt  截单时刻，前端做倒计时展示用（判定仍以服务端为准）
     */
    public record UserMenuView(LocalDate serveDate, boolean cutoff, boolean closed, boolean orderable,
                               java.time.LocalDateTime cutoffAt, List<MenuItemView> items) {
    }

    public record AdminMenuView(LocalDate serveDate, boolean cutoff, boolean closed,
                                java.time.LocalDateTime cutoffAt, boolean published,
                                List<MenuItemView> items) {
    }

    /**
     * 菜单里的一项。
     *
     * @param remainQty 剩余份数；limitQty 为 null（不限量）时也是 null，前端显示「不限量」
     */
    public record MenuItemView(Long comboId, String name, String cover, Integer price,
                               List<ComboDish> dishes,
                               List<ComboSnapshot.SpecGroupSnapshot> specGroups,
                               Integer limitQty, Integer soldQty, Integer remainQty, String status) {
    }

    /**
     * 全部菜单的一项。
     *
     * @param tomorrowSupplied 是否在明天那份菜单里，前端据此打「明日供应 / 暂不供应」标签
     */
    public record ComboAllItem(Long comboId, String name, String cover, String desc, Integer price,
                               List<ComboDish> dishes,
                               List<ComboSnapshot.SpecGroupSnapshot> specGroups,
                               boolean tomorrowSupplied) {
    }

    /**
     * 保存入参：一个盒饭一项。
     *
     * @param limitQty null 表示不限量
     */
    public record MenuInput(Long comboId, Integer limitQty) {
    }
}

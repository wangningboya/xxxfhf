package com.xxxfhf.meal.goods.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.audit.domain.AuditActions;
import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.dict.domain.DictCodes;
import com.xxxfhf.meal.dict.service.DictService;
import com.xxxfhf.meal.goods.domain.Combo;
import com.xxxfhf.meal.goods.domain.ComboDish;
import com.xxxfhf.meal.goods.domain.Dish;
import com.xxxfhf.meal.goods.domain.GoodsStatus;
import com.xxxfhf.meal.goods.domain.SpecGroup;
import com.xxxfhf.meal.goods.mapper.ComboMapper;
import com.xxxfhf.meal.goods.mapper.DishMapper;
import com.xxxfhf.meal.goods.mapper.SpecGroupMapper;
import com.xxxfhf.meal.order.domain.OrderUsageQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盒饭维护（售卖层）：用户真正下单买的东西。
 *
 * 金额一律整数**分**。商家在界面上按元填写，换算在前端做（元 × 100 取整），
 * 服务端只认分 —— 让小数进库是将来对不上账的根源。
 */
@Service
public class ComboService {

    private static final Logger log = LoggerFactory.getLogger(ComboService.class);

    /** 价格上限 9999.99 元。单份盒饭不可能过万，超过这个数一定是把小数量级填错了。 */
    private static final int MAX_PRICE_CENT = 999999;

    private final ComboMapper comboMapper;
    private final DishMapper dishMapper;
    private final SpecGroupMapper specGroupMapper;
    /**
     * 查「这个盒饭是否出现在历史订单里」要读 {@code order.items}，这件事属于订单域，
     * 所以查询在 {@link OrderUsageQuery} 里，本类只调一个计数方法。
     *
     * <p>不注入 OrderMapper：goods 包反向依赖订单实体或 Mapper 会让两个域互相咬住，
     * 后续拆模块时第一个卡住的就是这里；而 OrderUsageQuery 只依赖 JdbcTemplate，
     * 谁注入它都不会成环。
     */
    private final OrderUsageQuery orderUsageQuery;
    /** 商品变更留痕（需求 4.19 / F-J13）：盒饭是售卖层，改价与改组成都会被用户直接看到 */
    private final AuditService auditService;
    /** 商品分类取字典校验（需求 4.22）：分类只影响展示分组，但必须是个启用着的字典项 */
    private final DictService dictService;

    public ComboService(ComboMapper comboMapper, DishMapper dishMapper,
                        SpecGroupMapper specGroupMapper, OrderUsageQuery orderUsageQuery,
                        AuditService auditService, DictService dictService) {
        this.comboMapper = comboMapper;
        this.dishMapper = dishMapper;
        this.specGroupMapper = specGroupMapper;
        this.orderUsageQuery = orderUsageQuery;
        this.auditService = auditService;
        this.dictService = dictService;
    }

    /** 盒饭列表（商家端）：上下架的都要看得到，否则没法重新上架。 */
    public List<Combo> list(String keyword, String status) {
        return list(keyword, status, null);
    }

    /**
     * 盒饭列表，可按商品分类筛选（需求 4.22）。
     *
     * @param categoryCode 为空表示不按分类筛；**不做字典存在性校验** ——
     *                     项被停用后，历史数据仍要能按原存值查出来
     */
    public List<Combo> list(String keyword, String status, String categoryCode) {
        String kw = keyword == null ? "" : keyword.trim();
        return comboMapper.selectList(Wrappers.<Combo>lambdaQuery()
                .like(!kw.isEmpty(), Combo::getName, kw)
                .eq(status != null && !status.isBlank(), Combo::getStatus, status)
                .eq(categoryCode != null && !categoryCode.isBlank(),
                        Combo::getCategoryCode, categoryCode)
                .orderByAsc(Combo::getSort)
                .orderByAsc(Combo::getName));
    }

    /** 上架中的盒饭（用户端「全部菜单」用）。下架即彻底隐藏。 */
    public List<Combo> listOnSale() {
        return comboMapper.selectList(Wrappers.<Combo>lambdaQuery()
                .eq(Combo::getStatus, GoodsStatus.ON)
                .orderByAsc(Combo::getSort)
                .orderByAsc(Combo::getName));
    }

    /** 全部盒饭（含下架），菜单管理页组装列表用。 */
    public List<Combo> listAll() {
        return list(null, null);
    }

    public Combo detail(Long id) {
        return require(id);
    }

    @Transactional
    public Combo save(ComboCommand cmd) {
        String name = requireName(cmd.name());
        int price = requirePrice(cmd.price());
        List<ComboDish> dishes = normalizeDishes(cmd.dishes());
        List<Long> specGroupIds = normalizeSpecGroupIds(cmd.specGroupIds());
        String categoryCode = requireCategory(cmd.categoryCode());

        boolean creating = cmd.id() == null;
        Combo combo;
        if (creating) {
            combo = new Combo();
            // 新建默认下架：先把组成和价格配好再上架，避免半成品直接出现在用户端
            combo.setStatus(GoodsStatus.OFF);
            combo.setSort(cmd.sort() == null ? 0 : cmd.sort());
        } else {
            combo = require(cmd.id());
            if (cmd.sort() != null) {
                combo.setSort(cmd.sort());
            }
        }

        combo.setName(name);
        combo.setCover(trimToNull(cmd.cover()));
        combo.setDesc(trimToNull(cmd.desc()));
        combo.setPrice(price);
        combo.setDishes(dishes);
        combo.setSpecGroupIds(specGroupIds);
        combo.setCategoryCode(categoryCode);

        if (creating) {
            comboMapper.insert(combo);
            log.info("新增盒饭 id={} name={} price={}分", combo.getId(), name, price);
        } else {
            comboMapper.updateById(combo);
            log.info("编辑盒饭 id={} name={} price={}分", combo.getId(), name, price);
        }
        auditService.record(AuditActions.TABLE_COMBO, combo.getId(),
                creating ? AuditActions.COMBO_CREATE : AuditActions.COMBO_UPDATE,
                (creating ? "新增盒饭：" : "修改盒饭：") + name + "｜价格 " + price
                        + " 分，分类 " + categoryCode + "，菜品 " + dishes.size() + " 项，规格组 "
                        + specGroupIds.size() + " 个");
        return combo;
    }

    /** 上架 / 下架。下架不影响已下单的订单（订单存的是快照）。 */
    @Transactional
    public Combo updateStatus(Long id, String status) {
        if (!GoodsStatus.ON.equals(status) && !GoodsStatus.OFF.equals(status)) {
            throw BizException.param("状态只能是 on 或 off");
        }
        Combo combo = require(id);
        combo.setStatus(status);
        comboMapper.updateById(combo);
        log.info("盒饭状态变更 id={} -> {}", id, status);
        auditService.record(AuditActions.TABLE_COMBO, id, AuditActions.COMBO_STATUS,
                "盒饭「" + combo.getName() + "」" + (GoodsStatus.ON.equals(status) ? "上架" : "下架"));
        return combo;
    }

    /**
     * 删除。**历史订单引用过的盒饭只能下架不能删除** ——
     * 订单明细里虽然存了快照，但删掉整份盒饭会让「这个盒饭卖过多少」永久失去追溯入口。
     */
    @Transactional
    public void delete(Long id) {
        Combo combo = require(id);
        // 引用计数由订单域算（读 order.items），本类不再拼 order 表的 SQL
        long used = orderUsageQuery.countOrdersUsingCombo(id);
        if (used > 0) {
            throw BizException.conflict("「" + combo.getName() + "」已被 " + used
                    + " 笔历史订单引用，只能下架不能删除");
        }
        comboMapper.deleteById(id);
        log.info("删除盒饭 id={} name={}", id, combo.getName());
        auditService.record(AuditActions.TABLE_COMBO, id, AuditActions.COMBO_DELETE,
                "删除盒饭：" + combo.getName());
    }

    public Combo require(Long id) {
        Combo combo = id == null ? null : comboMapper.selectById(id);
        if (combo == null) {
            throw BizException.notFound("盒饭不存在");
        }
        return combo;
    }

    /**
     * 组成菜品清洗。
     *
     * 三个要点：
     * 1. **菜名从菜品库取，不信前端传的 name** —— 否则前端可以往订单快照里写任意文字
     * 2. 同一个菜不能出现两次（份数用 qty 表达即可），重复多半是误操作
     * 3. 停用的菜品不能**新加进**盒饭；但已在盒饭里的允许保留，否则商家改个价格都会被拦
     */
    private List<ComboDish> normalizeDishes(List<ComboDish> raw) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.param("盒饭至少要包含一道菜");
        }
        Map<Long, Dish> cache = new HashMap<>();
        List<ComboDish> result = new ArrayList<>(raw.size());
        Set<Long> seen = new HashSet<>();

        for (ComboDish item : raw) {
            Long dishId = item == null ? null : item.dishId();
            if (dishId == null) {
                throw BizException.param("组成菜品缺少菜品 ID");
            }
            if (!seen.add(dishId)) {
                throw BizException.param("同一道菜不能重复添加，份数请用加减号调整");
            }
            Dish dish = cache.computeIfAbsent(dishId, dishMapper::selectById);
            if (dish == null) {
                throw BizException.param("菜品不存在或已被删除：" + dishId);
            }
            if (GoodsStatus.DISABLED.equals(dish.getStatus())) {
                throw BizException.param("「" + dish.getName() + "」已停用，不能加入盒饭");
            }
            int qty = item.qtyOrDefault();
            if (qty > 99) {
                throw BizException.param("单个菜品份数最多 99 份");
            }
            result.add(new ComboDish(dish.getId(), dish.getName(), qty));
        }
        return result;
    }

    /** 规格组只能是启用中的；停用组不允许被新启用（停用会把引用一起解除）。 */
    private List<Long> normalizeSpecGroupIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> result = new ArrayList<>(raw.size());
        Set<Long> seen = new HashSet<>();
        for (Long id : raw) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            SpecGroup group = specGroupMapper.selectById(id);
            if (group == null) {
                throw BizException.param("规格组不存在：" + id);
            }
            if (!GoodsStatus.ENABLED.equals(group.getStatus())) {
                throw BizException.param("「" + group.getName() + "」已停用，不能启用");
            }
            result.add(id);
        }
        return result;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw BizException.param("请填写盒饭名称");
        }
        if (name.length() > 64) {
            throw BizException.param("盒饭名称最多 64 个字");
        }
        return name;
    }

    private static int requirePrice(Integer price) {
        if (price == null) {
            throw BizException.param("请填写盒饭价格");
        }
        if (price < 0) {
            throw BizException.param("价格不能为负数");
        }
        if (price > MAX_PRICE_CENT) {
            throw BizException.param("价格不能超过 9999.99 元，请检查是否填错数量级");
        }
        return price;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 商品分类必填（需求 4.22）：取字典 {@code combo_category} 里启用着的项，存的是**存值**不是项 ID。
     *
     * <p>只做存在性校验：分类不参与履约，代码里没有任何按分类分叉的逻辑，
     * 所以这里不会因为它换了值而多出别的分支。
     */
    private String requireCategory(String raw) {
        return dictService.requireValue(DictCodes.COMBO_CATEGORY, "商品分类", raw, "保存盒饭");
    }

    /**
     * 保存入参。
     *
     * @param price        整数**分**，前端负责从元换算
     * @param dishes       只需传 dishId 与 qty；name 由服务端按菜品库回填
     * @param specGroupIds 启用的规格组；空数组表示这份盒饭没有规格
     * @param categoryCode 商品分类存值（字典 combo_category），必填
     * @param sort         编辑时传 null 表示不修改排序
     */
    public record ComboCommand(Long id, String name, String cover, String desc, Integer price,
                               List<ComboDish> dishes, List<Long> specGroupIds,
                               String categoryCode, Integer sort) {
    }
}

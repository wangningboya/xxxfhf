package com.xxxfhf.meal.goods;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜品库（原料层）。
 *
 * 两条规则贯穿全类：
 * 1. **菜品不单独定价**，价格挂在盒饭上；这里根本没有价格字段
 * 2. **被任一盒饭引用就不能删**，只能停用 —— 停用后从盒饭选择器里消失，
 *    但已经把它配进盒饭的不受影响（需求 4.2）
 */
@Service
public class DishService {

    private static final Logger log = LoggerFactory.getLogger(DishService.class);

    private final DishMapper dishMapper;
    private final ComboMapper comboMapper;

    public DishService(DishMapper dishMapper, ComboMapper comboMapper) {
        this.dishMapper = dishMapper;
        this.comboMapper = comboMapper;
    }

    /** 菜品列表。商家端用，停用的也要看得到（否则没法重新启用）。 */
    public List<Dish> list(String keyword, String status) {
        String kw = keyword == null ? "" : keyword.trim();
        return dishMapper.selectList(Wrappers.<Dish>lambdaQuery()
                .like(!kw.isEmpty(), Dish::getName, kw)
                .eq(status != null && !status.isBlank(), Dish::getStatus, status)
                .orderByAsc(Dish::getSort)
                .orderByAsc(Dish::getName));
    }

    /**
     * 新增 / 编辑菜品。
     *
     * @param id 为空表示新增；非空表示编辑
     */
    @Transactional
    public Dish save(DishCommand cmd) {
        String name = requireName(cmd.name());
        boolean creating = cmd.id() == null || cmd.id().isBlank();

        Dish dish;
        if (creating) {
            dish = new Dish();
            dish.setStatus(GoodsStatus.ENABLED);
            dish.setSort(cmd.sort() == null ? 0 : cmd.sort());
        } else {
            dish = require(cmd.id());
            // 编辑时 sort 传 null 表示「不动」，而不是「归零」——
            // 否则前端只改个名字就会把排序清掉
            if (cmd.sort() != null) {
                dish.setSort(cmd.sort());
            }
        }
        dish.setName(name);
        dish.setCategoryId(trimToNull(cmd.categoryId()));

        if (creating) {
            dishMapper.insert(dish);
            log.info("新增菜品 id={} name={}", dish.getId(), name);
        } else {
            dishMapper.updateById(dish);
            log.info("编辑菜品 id={} name={}", dish.getId(), name);
        }
        return dish;
    }

    /** 启用 / 停用。停用不会影响已把它配进盒饭的那些盒饭。 */
    @Transactional
    public Dish updateStatus(String id, String status) {
        requireStatus(status);
        Dish dish = require(id);
        dish.setStatus(status);
        dishMapper.updateById(dish);
        log.info("菜品状态变更 id={} -> {}", id, status);
        return dish;
    }

    /** 删除。被盒饭引用时拒绝，并点名是哪几个盒饭，让商家能判断该不该停用。 */
    @Transactional
    public void delete(String id) {
        Dish dish = require(id);
        List<String> usedBy = comboNamesUsing(id);
        if (!usedBy.isEmpty()) {
            throw BizException.conflict("「" + dish.getName() + "」正被 " + usedBy.size()
                    + " 个盒饭引用（" + preview(usedBy) + "），只能停用不能删除");
        }
        dishMapper.deleteById(id);
        log.info("删除菜品 id={} name={}", id, dish.getName());
    }

    public Dish require(String id) {
        Dish dish = id == null ? null : dishMapper.selectById(id);
        if (dish == null) {
            throw BizException.notFound("菜品不存在");
        }
        return dish;
    }

    /**
     * 哪些盒饭引用了这道菜。
     *
     * 盒饭的组成存在 JSON 列里，没有关联表可 join，所以整表取回在内存里比。
     * 单店盒饭数量在几十个量级，这个代价可以接受；真到上千个再考虑冗余一张关联表。
     */
    private List<String> comboNamesUsing(String dishId) {
        List<Combo> combos = comboMapper.selectList(Wrappers.<Combo>lambdaQuery()
                .select(Combo::getId, Combo::getName, Combo::getDishes));
        List<String> names = new ArrayList<>();
        for (Combo combo : combos) {
            if (combo.getDishes() == null) {
                continue;
            }
            for (ComboDish item : combo.getDishes()) {
                if (dishId.equals(item.dishId())) {
                    names.add(combo.getName());
                    break;
                }
            }
        }
        return names;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw BizException.param("请填写菜品名称");
        }
        if (name.length() > 64) {
            throw BizException.param("菜品名称最多 64 个字");
        }
        return name;
    }

    private static void requireStatus(String status) {
        if (!GoodsStatus.ENABLED.equals(status) && !GoodsStatus.DISABLED.equals(status)) {
            throw BizException.param("状态只能是 enabled 或 disabled");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 错误文案里最多点三个名字，多了用「等」收尾，避免异常信息比屏幕还长。 */
    private static String preview(List<String> names) {
        if (names.size() <= 3) {
            return String.join("、", names);
        }
        return String.join("、", names.subList(0, 3)) + " 等";
    }

    /**
     * 保存入参。
     *
     * @param id         为空 = 新增
     * @param sort       编辑时传 null 表示不修改排序
     */
    public record DishCommand(String id, String name, String categoryId, Integer sort) {
    }
}

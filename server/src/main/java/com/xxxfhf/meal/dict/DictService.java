package com.xxxfhf.meal.dict;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字典服务。
 *
 * 三条设计取舍：
 *
 * 1. **展示名与存值分离**：`item_value` 写进业务表，`item_label` 只用于展示。
 *    改文案不动数据，这是字典存在的唯一理由。
 * 2. **内置类型默认不许增删项**：往 `order_status` 里加一个库里不存在的值，
 *    订单状态机会出现无法处理的数据。内置类型只允许改显示名 / 颜色 / 排序 / 启停。
 *    例外见 {@link #EXTENSIBLE_TYPES}：收款方式不是状态机的一环，允许增删。
 * 3. **进程内缓存，写后清空**：订单列表每行要算两个标签，不缓存等于每单多查两次库。
 *    单实例部署，写操作清空缓存即可保证一致；将来多实例要换成分布式缓存或短 TTL。
 */
@Service
public class DictService {

    /**
     * 允许商家自行增删字典项的**内置**类型。
     *
     * <p>只放收款方式：它只是个收款渠道的标签，多一个「银行转账」不会让任何代码
     * 走到不认识的分支；其余内置类型的值与枚举一一对应（订单状态、收款状态等），
     * 增一项就等于往状态机里塞一个处理不了的值。
     *
     * <p>类型本身仍是内置的：不可删除、编码不可改 —— 否则商家一删，
     * 下单页的下拉直接空掉，且所有历史订单的显示名一起落空。
     */
    private static final Set<String> EXTENSIBLE_TYPES = Set.of(DictCodes.PAY_METHOD);

    private static final Logger log = LoggerFactory.getLogger(DictService.class);

    private final DictTypeMapper typeMapper;
    private final DictItemMapper itemMapper;

    /** typeCode -> (itemValue -> item)，整块替换，避免读一半被写穿 */
    private volatile Map<String, Map<String, DictItem>> cache;

    public DictService(DictTypeMapper typeMapper, DictItemMapper itemMapper) {
        this.typeMapper = typeMapper;
        this.itemMapper = itemMapper;
    }

    /* ==================== 读：给业务代码用 ==================== */

    /**
     * 取显示名。查不到时回退到调用方给的兜底值，再不行就返回存值本身。
     *
     * **绝不抛异常**：字典是展示层的事，缺一条字典不该让订单列表打不开。
     */
    public String label(String typeCode, String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        DictItem item = item(typeCode, value);
        if (item != null && item.getItemLabel() != null && !item.getItemLabel().isBlank()) {
            return item.getItemLabel();
        }
        return fallback == null ? value : fallback;
    }

    /** 取前端配色，查不到返回 null 由前端按默认样式渲染。 */
    public String tagType(String typeCode, String value) {
        DictItem item = item(value == null ? null : typeCode, value);
        return item == null ? null : item.getTagType();
    }

    /**
     * 校验一个存值是不是「该类型下启用着的字典项」，并返回规范化后的值。
     *
     * <p>给需要落库的字段用（当前只有收款方式）：字典可以增删项，代码里就不能再写死
     * 三个常量做校验，否则商家新增的项一点就 400。
     *
     * <p>空值与停用项都当不合法：停用意味着商家明确不想让人再选它，老订单上的值照旧
     * 显示（{@link #label} 不走这里），但不能拿它提交新操作。
     *
     * @param typeName    类型中文名，只用于报错文案
     * @param actionLabel 动作名，拼成「请选择收款方式后才能标记付款」
     */
    public String requireValue(String typeCode, String typeName, String value, String actionLabel) {
        String code = value == null ? "" : value.trim();
        if (code.isEmpty()) {
            throw BizException.param("请选择" + typeName + "后才能" + actionLabel);
        }
        if (item(typeCode, code) == null) {
            throw BizException.param(typeName + "「" + code + "」已停用或不存在，请重新选择");
        }
        return code;
    }

    private DictItem item(String typeCode, String value) {
        if (typeCode == null || value == null) {
            return null;
        }
        Map<String, DictItem> items = snapshot().get(typeCode);
        return items == null ? null : items.get(value);
    }

    private Map<String, Map<String, DictItem>> snapshot() {
        Map<String, Map<String, DictItem>> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cache == null) {
                cache = loadAll();
            }
            return cache;
        }
    }

    private Map<String, Map<String, DictItem>> loadAll() {
        List<DictItem> items = itemMapper.selectList(Wrappers.<DictItem>lambdaQuery()
                .eq(DictItem::getStatus, "active")
                .orderByAsc(DictItem::getSort));
        Map<String, Map<String, DictItem>> result = new LinkedHashMap<>();
        for (DictItem item : items) {
            result.computeIfAbsent(item.getTypeCode(), k -> new LinkedHashMap<>())
                    .put(item.getItemValue(), item);
        }
        return result;
    }

    /** 写操作后统一清空，下次读重建。 */
    private void evict() {
        cache = null;
    }

    /* ==================== 读：给前端用 ==================== */

    /**
     * 一次下发全部启用的字典，前端进小程序时取一次缓存在本地。
     *
     * 之所以不做「按类型逐个取」：类型只有 8 个、项不到 40 条，
     * 一次拿全比逐类型请求省事，也省掉前端拼类型参数的出错机会。
     */
    public Map<String, List<ItemView>> clientDict() {
        List<DictItem> items = itemMapper.selectList(Wrappers.<DictItem>lambdaQuery()
                .eq(DictItem::getStatus, "active")
                .orderByAsc(DictItem::getSort));
        Map<String, List<ItemView>> result = new LinkedHashMap<>();
        for (DictItem item : items) {
            result.computeIfAbsent(item.getTypeCode(), k -> new ArrayList<>())
                    .add(ItemView.of(item));
        }
        return result;
    }

    /* ==================== 管理端 ==================== */

    public List<TypeView> listTypes() {
        List<DictType> types = typeMapper.selectList(Wrappers.<DictType>lambdaQuery()
                .orderByAsc(DictType::getTypeCode));
        List<DictItem> items = itemMapper.selectList(null);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DictItem item : items) {
            counts.merge(item.getTypeCode(), 1, Integer::sum);
        }
        return types.stream()
                .map(t -> TypeView.of(t, counts.getOrDefault(t.getTypeCode(), 0)))
                .toList();
    }

    public List<ItemView> listItems(String typeCode) {
        List<DictItem> items = itemMapper.selectList(Wrappers.<DictItem>lambdaQuery()
                .eq(DictItem::getTypeCode, typeCode)
                .orderByAsc(DictItem::getSort));
        return items.stream().map(ItemView::of).sorted(
                Comparator.comparing(ItemView::sort, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Transactional
    public TypeView saveType(SaveTypeCommand cmd) {
        String code = trim(cmd.typeCode());
        if (code.isEmpty()) {
            throw BizException.param("请填写类型编码");
        }
        DictType exists = typeMapper.selectById(code);
        if (exists != null && Boolean.TRUE.equals(exists.getBuiltin())) {
            // 内置类型的编码被代码引用，改名等于让所有 label() 调用落空
            exists.setTypeName(trim(cmd.typeName()));
            exists.setRemark(trimToNull(cmd.remark()));
            typeMapper.updateById(exists);
            evict();
            return TypeView.of(exists, countItems(code));
        }
        DictType entity = exists == null ? new DictType() : exists;
        entity.setTypeCode(code);
        entity.setTypeName(trim(cmd.typeName()));
        entity.setRemark(trimToNull(cmd.remark()));
        if (exists == null) {
            entity.setBuiltin(false);
            typeMapper.insert(entity);
        } else {
            typeMapper.updateById(entity);
        }
        evict();
        log.info("字典类型保存 code={} 新建={}", code, exists == null);
        return TypeView.of(entity, countItems(code));
    }

    @Transactional
    public void deleteType(String typeCode) {
        DictType type = typeMapper.selectById(typeCode);
        if (type == null) {
            throw BizException.notFound("字典类型不存在");
        }
        if (Boolean.TRUE.equals(type.getBuiltin())) {
            throw BizException.conflict("内置类型不可删除，可改为停用其字典项");
        }
        itemMapper.delete(Wrappers.<DictItem>lambdaQuery().eq(DictItem::getTypeCode, typeCode));
        typeMapper.deleteById(typeCode);
        evict();
        log.info("字典类型删除 code={}", typeCode);
    }

    /**
     * 保存字典项。
     *
     * 新建 / 修改的分支都收在这里，是因为内置项的约束不对称：
     * 内置项**可以改显示名、颜色、排序、启停**，但不能改存值、不能删。
     * 拆成两个方法会出现「新增走了宽松路径」的漏洞。
     */
    @Transactional
    public ItemView saveItem(SaveItemCommand cmd) {
        String typeCode = trim(cmd.typeCode());
        DictType type = typeMapper.selectById(typeCode);
        if (type == null) {
            throw BizException.notFound("字典类型不存在");
        }
        boolean builtinType = Boolean.TRUE.equals(type.getBuiltin());
        String value = trim(cmd.itemValue());
        if (value.isEmpty()) {
            throw BizException.param("请填写字典值");
        }

        DictItem exists = cmd.id() == null ? null : itemMapper.selectById(cmd.id());
        if (exists == null) {
            if (builtinType && !EXTENSIBLE_TYPES.contains(typeCode)) {
                throw BizException.conflict("内置类型不支持新增字典值，只能修改已有项的显示名");
            }
            DictItem duplicate = itemMapper.selectOne(Wrappers.<DictItem>lambdaQuery()
                    .eq(DictItem::getTypeCode, typeCode)
                    .eq(DictItem::getItemValue, value));
            if (duplicate != null) {
                throw BizException.conflict("该类型下已存在相同的字典值");
            }
            DictItem entity = new DictItem();
            entity.setTypeCode(typeCode);
            entity.setItemValue(value);
            entity.setItemLabel(trim(cmd.itemLabel()));
            entity.setTagType(trimToNull(cmd.tagType()));
            entity.setSort(cmd.sort() == null ? 0 : cmd.sort());
            entity.setStatus(normalizeStatus(cmd.status()));
            entity.setBuiltin(false);
            itemMapper.insert(entity);
            evict();
            log.info("字典项新增 type={} value={}", typeCode, value);
            return ItemView.of(entity);
        }

        boolean builtinItem = Boolean.TRUE.equals(exists.getBuiltin());
        if (builtinItem && !exists.getItemValue().equals(value)) {
            throw BizException.conflict("内置字典项的存值不可修改，只能改显示名");
        }
        exists.setItemValue(value);
        exists.setItemLabel(trim(cmd.itemLabel()));
        exists.setTagType(trimToNull(cmd.tagType()));
        if (cmd.sort() != null) {
            exists.setSort(cmd.sort());
        }
        if (cmd.status() != null && !cmd.status().isBlank()) {
            exists.setStatus(normalizeStatus(cmd.status()));
        }
        itemMapper.updateById(exists);
        evict();
        log.info("字典项修改 id={} type={} value={}", exists.getId(), typeCode, value);
        return ItemView.of(exists);
    }

    @Transactional
    public void deleteItem(String id) {
        DictItem item = itemMapper.selectById(id);
        if (item == null) {
            throw BizException.notFound("字典项不存在");
        }
        if (Boolean.TRUE.equals(item.getBuiltin())) {
            throw BizException.conflict("内置字典项不可删除，可改为停用");
        }
        itemMapper.deleteById(id);
        evict();
        log.info("字典项删除 id={} type={} value={}", id, item.getTypeCode(), item.getItemValue());
    }

    /** 单独改启停，列表里点开关用，省一次整表单提交。 */
    @Transactional
    public ItemView setItemStatus(String id, String status) {
        DictItem item = itemMapper.selectById(id);
        if (item == null) {
            throw BizException.notFound("字典项不存在");
        }
        item.setStatus(normalizeStatus(status));
        itemMapper.updateById(item);
        evict();
        return ItemView.of(item);
    }

    private int countItems(String typeCode) {
        return Math.toIntExact(itemMapper.selectCount(Wrappers.<DictItem>lambdaQuery()
                .eq(DictItem::getTypeCode, typeCode)));
    }

    private static String normalizeStatus(String status) {
        return "disabled".equals(status) ? "disabled" : "active";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /* ==================== 出参 / 入参 ==================== */

    public record ItemView(String id, String typeCode, String itemValue, String itemLabel,
                           String tagType, Integer sort, String status, boolean builtin) {

        static ItemView of(DictItem item) {
            return new ItemView(item.getId(), item.getTypeCode(), item.getItemValue(),
                    item.getItemLabel(), item.getTagType(), item.getSort(),
                    item.getStatus(), Boolean.TRUE.equals(item.getBuiltin()));
        }
    }

    /**
     * @param builtin    内置类型的编码被代码引用，不可删除、不可改编码
     * @param extensible 允许商家自行增删项（内置类型里的例外，目前只有收款方式）。
     *                   前端据此决定「新增项」按钮是否可点 —— 否则后端放行了、
     *                   页面上按钮却是灰的，等于没放开。
     */
    public record TypeView(String typeCode, String typeName, boolean builtin, boolean extensible,
                           String remark, int itemCount) {

        static TypeView of(DictType type, int itemCount) {
            return new TypeView(type.getTypeCode(), type.getTypeName(),
                    Boolean.TRUE.equals(type.getBuiltin()),
                    EXTENSIBLE_TYPES.contains(type.getTypeCode()),
                    type.getRemark(), itemCount);
        }
    }

    public record SaveTypeCommand(String typeCode, String typeName, String remark) {
    }

    public record SaveItemCommand(String id, String typeCode, String itemValue, String itemLabel,
                                  String tagType, Integer sort, String status) {
    }
}

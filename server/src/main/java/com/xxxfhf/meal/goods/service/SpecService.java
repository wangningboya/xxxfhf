package com.xxxfhf.meal.goods.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.audit.domain.AuditActions;
import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.goods.domain.Combo;
import com.xxxfhf.meal.goods.domain.GoodsStatus;
import com.xxxfhf.meal.goods.domain.SpecGroup;
import com.xxxfhf.meal.goods.domain.SpecOption;
import com.xxxfhf.meal.goods.mapper.ComboMapper;
import com.xxxfhf.meal.goods.mapper.SpecGroupMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 规格组维护（网页后台独有模块，小程序内只能选用不能新建）。
 *
 * 停用是本类最需要小心的地方：**停用必须把引用它的盒饭一起解除引用**，
 * 不能留下「组已停用但盒饭还认为启用了」的隐性状态 —— 那会卖出没有主食的盒饭。
 * 所以停用分两步：不带确认先返回 409 + 受影响清单，商家确认后带 confirmUnbind 再来一次。
 */
@Service
public class SpecService {

    private static final Logger log = LoggerFactory.getLogger(SpecService.class);

    private final SpecGroupMapper specGroupMapper;
    private final ComboMapper comboMapper;
    /** 规格组变更留痕（需求 4.19 / F-J13）：改差价会直接改用户看到的价，停用会连带解除引用 */
    private final AuditService auditService;

    public SpecService(SpecGroupMapper specGroupMapper, ComboMapper comboMapper,
                       AuditService auditService) {
        this.specGroupMapper = specGroupMapper;
        this.comboMapper = comboMapper;
        this.auditService = auditService;
    }

    /** 全部规格组。停用的也返回，否则后台没法把它重新启用。 */
    public List<SpecGroup> list() {
        return specGroupMapper.selectList(Wrappers.<SpecGroup>lambdaQuery()
                .orderByAsc(SpecGroup::getSort)
                .orderByAsc(SpecGroup::getName));
    }

    /** 只返回启用中的组，供盒饭编辑页的选择器使用。 */
    public List<SpecGroup> listEnabled() {
        return specGroupMapper.selectList(Wrappers.<SpecGroup>lambdaQuery()
                .eq(SpecGroup::getStatus, GoodsStatus.ENABLED)
                .orderByAsc(SpecGroup::getSort)
                .orderByAsc(SpecGroup::getName));
    }

    @Transactional
    public SpecGroup save(SpecCommand cmd) {
        String name = requireName(cmd.name());
        boolean creating = cmd.id() == null;

        SpecGroup group;
        if (creating) {
            group = new SpecGroup();
            group.setStatus(GoodsStatus.ENABLED);
            group.setSort(cmd.sort() == null ? 0 : cmd.sort());
            group.setCreatedAt(LocalDateTime.now());
        } else {
            group = require(cmd.id());
            if (cmd.sort() != null) {
                group.setSort(cmd.sort());
            }
        }

        group.setName(name);
        group.setRequired(cmd.required() == null ? Boolean.FALSE : cmd.required());
        group.setCountInPrep(cmd.countInPrep() == null ? Boolean.TRUE : cmd.countInPrep());
        group.setOptions(normalizeOptions(cmd.options()));

        if (creating) {
            specGroupMapper.insert(group);
            log.info("新增规格组 id={} name={}", group.getId(), name);
        } else {
            specGroupMapper.updateById(group);
            log.info("编辑规格组 id={} name={}", group.getId(), name);
        }
        auditService.record(AuditActions.TABLE_SPEC, group.getId(),
                creating ? AuditActions.SPEC_CREATE : AuditActions.SPEC_UPDATE,
                (creating ? "新增规格组：" : "修改规格组：") + name + "｜选项 "
                        + group.getOptions().size() + " 个"
                        + (Boolean.TRUE.equals(group.getRequired()) ? "，必选" : "，可选"));
        return group;
    }

    /**
     * 启用 / 停用。
     *
     * @param confirmUnbind 停用且被盒饭引用时，必须为 true 才会真的解除引用。
     *                      为 false 直接 409，把受影响盒饭清单写进提示文案里。
     */
    @Transactional
    public SpecGroup updateStatus(Long id, String status, boolean confirmUnbind) {
        requireStatus(status);
        SpecGroup group = require(id);

        // 解除引用后就查不到「原来有几个」了，先记下数量再动
        int unbound = 0;
        if (GoodsStatus.DISABLED.equals(status)) {
            List<Combo> refs = combosReferencing(id);
            unbound = refs.size();
            if (!refs.isEmpty() && !confirmUnbind) {
                throw BizException.conflict("「" + group.getName() + "」正被 " + refs.size()
                        + " 个盒饭引用（" + preview(refs) + "）。停用会一并解除这些盒饭对该组的引用，"
                        + "请确认后重新提交");
            }
            for (Combo combo : refs) {
                List<Long> ids = combo.getSpecGroupIds() == null
                        ? new ArrayList<>() : new ArrayList<>(combo.getSpecGroupIds());
                ids.remove(id);
                combo.setSpecGroupIds(ids);
                comboMapper.updateById(combo);
                log.info("解除盒饭对规格组的引用 comboId={} specGroupId={}", combo.getId(), id);
            }
        }

        group.setStatus(status);
        specGroupMapper.updateById(group);
        log.info("规格组状态变更 id={} -> {}", id, status);
        auditService.record(AuditActions.TABLE_SPEC, id, AuditActions.SPEC_STATUS,
                "规格组「" + group.getName() + "」"
                        + (GoodsStatus.DISABLED.equals(status) ? "停用" : "启用")
                        + (unbound > 0 ? "，同时解除 " + unbound + " 个盒饭的引用" : ""));
        return group;
    }

    /** 停用前的二次确认用：列出会被连带解除引用的盒饭。 */
    public List<ComboBrief> affectedCombos(Long specGroupId) {
        require(specGroupId);
        return combosReferencing(specGroupId).stream()
                .map(c -> new ComboBrief(c.getId(), c.getName(), c.getStatus()))
                .toList();
    }

    /** 删除。被任一盒饭引用时拒绝 —— 只能停用（停用会连带解除引用）。 */
    @Transactional
    public void delete(Long id) {
        SpecGroup group = require(id);
        List<Combo> refs = combosReferencing(id);
        if (!refs.isEmpty()) {
            throw BizException.conflict("「" + group.getName() + "」正被 " + refs.size()
                    + " 个盒饭引用（" + preview(refs) + "），只能停用不能删除");
        }
        specGroupMapper.deleteById(id);
        log.info("删除规格组 id={} name={}", id, group.getName());
        auditService.record(AuditActions.TABLE_SPEC, id, AuditActions.SPEC_DELETE,
                "删除规格组：" + group.getName());
    }

    public SpecGroup require(Long id) {
        SpecGroup group = id == null ? null : specGroupMapper.selectById(id);
        if (group == null) {
            throw BizException.notFound("规格组不存在");
        }
        return group;
    }

    private List<Combo> combosReferencing(Long specGroupId) {
        List<Combo> combos = comboMapper.selectList(Wrappers.<Combo>lambdaQuery()
                .select(Combo::getId, Combo::getName, Combo::getStatus, Combo::getSpecGroupIds));
        List<Combo> hits = new ArrayList<>();
        for (Combo combo : combos) {
            if (combo.getSpecGroupIds() != null && combo.getSpecGroupIds().contains(specGroupId)) {
                hits.add(combo);
            }
        }
        return hits;
    }

    /**
     * 选项清洗：要求至少一项；optionId 缺失时由服务端生成。
     *
     * 为什么不信任前端传来的 optionId：选项 ID 是订单快照里记录「当时选了哪个」的唯一凭据，
     * 两个组出现同一个 optionId 时备菜会把两批数据合并，商家永远对不上账。
     */
    private static List<SpecOption> normalizeOptions(List<SpecOption> raw) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.param("规格组至少要有一个选项");
        }
        List<SpecOption> result = new ArrayList<>(raw.size());
        for (SpecOption option : raw) {
            String name = option.name() == null ? "" : option.name().trim();
            if (name.isEmpty()) {
                throw BizException.param("选项名称不能为空");
            }
            if (name.length() > 32) {
                throw BizException.param("选项名称最多 32 个字");
            }
            int delta = option.deltaOrZero();
            if (delta < 0) {
                throw BizException.param("差价不能为负数；负差价等于变相打折，当前版本不支持");
            }
            String optionId = option.optionId() == null || option.optionId().isBlank()
                    ? UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                    : option.optionId().trim();
            result.add(new SpecOption(optionId, name, delta, option.sortOrZero()));
        }
        return result;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw BizException.param("请填写规格组名称");
        }
        if (name.length() > 64) {
            throw BizException.param("规格组名称最多 64 个字");
        }
        return name;
    }

    private static void requireStatus(String status) {
        if (!GoodsStatus.ENABLED.equals(status) && !GoodsStatus.DISABLED.equals(status)) {
            throw BizException.param("状态只能是 enabled 或 disabled");
        }
    }

    private static String preview(List<Combo> combos) {
        List<String> names = combos.stream().map(Combo::getName).toList();
        if (names.size() <= 3) {
            return String.join("、", names);
        }
        return String.join("、", names.subList(0, 3)) + " 等";
    }

    /**
     * @param sort        组展示顺序，编辑时传 null 表示不修改
     * @param countInPrep 是否计入备菜；不传默认 true（主食这类物料型规格的常态）
     */
    public record SpecCommand(Long id, String name, Boolean required, Boolean countInPrep,
                              List<SpecOption> options, Integer sort) {
    }

    /** 受影响盒饭的简要信息，只给确认弹窗看。 */
    public record ComboBrief(Long comboId, String name, String status) {
    }
}

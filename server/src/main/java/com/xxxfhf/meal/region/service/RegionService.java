package com.xxxfhf.meal.region.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.order.domain.OrderUsageQuery;
import com.xxxfhf.meal.region.domain.Region;
import com.xxxfhf.meal.region.mapper.RegionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 区域管理。只有商家能建、能改、能停用，用户无权创建区域。
 *
 * **删除保护是这里的核心**：区域下有地址、或已被历史订单引用，都只能停用不能删除。
 * 删掉会让历史备菜数据失去归集维度，事后对不上账且无法追溯。
 */
@Service
public class RegionService {

    private static final Logger log = LoggerFactory.getLogger(RegionService.class);

    private final RegionMapper regionMapper;
    /**
     * 订单引用计数属于订单域（要读 {@code order.region_id}），由 {@link OrderUsageQuery} 提供。
     *
     * <p>区域自己这张表之外，还剩一条 {@code address} 的计数留在本类用 JdbcTemplate 查：
     * address 包本来就依赖 region 包（新增地址要选区域），把那条也搬过去就成了包级双向依赖，
     * 比一行 SQL 的耦合更贵。
     */
    private final OrderUsageQuery orderUsageQuery;
    private final JdbcTemplate jdbcTemplate;

    public RegionService(RegionMapper regionMapper, OrderUsageQuery orderUsageQuery,
                         JdbcTemplate jdbcTemplate) {
        this.regionMapper = regionMapper;
        this.orderUsageQuery = orderUsageQuery;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 用户端：只返回启用中的区域（新增地址时的选择器用）。 */
    public List<Region> listEnabled() {
        return regionMapper.selectList(Wrappers.<Region>lambdaQuery()
                .eq(Region::getStatus, "enabled")
                .orderByAsc(Region::getSort)
                .orderByAsc(Region::getName));
    }

    /** 商家端：全部，含已停用（否则没法重新启用）。 */
    public List<Region> listAll() {
        return regionMapper.selectList(Wrappers.<Region>lambdaQuery()
                .orderByAsc(Region::getSort)
                .orderByAsc(Region::getName));
    }

    /**
     * 商家端区域列表，带每个区域下的地址数（N-44）。
     *
     * <p>地址数只做展示，不参与任何判定：商家在区域管理页点「查看地址」时要先知道有没有、
     * 有几个，否则得逐个展开试。停用区域前的二次确认也要用它说清影响面。
     *
     * <p>计数一次 `GROUP BY` 查完，不做 N+1 —— 15 个区域各查一次就够把页面拖慢了。
     */
    public List<RegionView> listAllView() {
        List<Region> regions = listAll();
        if (regions.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> counts = addressCountsOf(regions.stream().map(Region::getId).toList());
        List<RegionView> views = new ArrayList<>(regions.size());
        for (Region region : regions) {
            views.add(new RegionView(region.getId(), region.getName(), region.getRemark(),
                    region.getSort(), region.getStatus(), region.getCreatedAt(),
                    counts.getOrDefault(region.getId(), 0)));
        }
        return views;
    }

    /**
     * 批量取区域状态，供地址可用性判定使用（避免逐条地址各查一次区域）。
     *
     * <p>返回的是 `regionId -> enabled/disabled`。查不到的 id 不在 map 里，
     * 调用方按「不存在」处理 —— 地址表里挂了已删除区域的老数据不该把列表整页打挂。
     */
    public Map<Long, String> statusesOf(List<Long> regionIds) {
        if (regionIds == null || regionIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = regionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new HashMap<>(ids.size());
        for (Region region : regionMapper.selectList(Wrappers.<Region>lambdaQuery()
                .select(Region::getId, Region::getStatus)
                .in(Region::getId, ids))) {
            map.put(region.getId(), region.getStatus());
        }
        return map;
    }

    /** 一次查完所有区域的地址数；没有地址的区域不出现在结果里 */
    private Map<Long, Integer> addressCountsOf(List<Long> regionIds) {
        if (regionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(regionIds.size(), "?"));
        Map<Long, Integer> map = new HashMap<>(regionIds.size());
        RowCallbackHandler collect = rs -> map.put(rs.getLong("region_id"), rs.getInt("cnt"));
        jdbcTemplate.query(
                "SELECT region_id, COUNT(*) AS cnt FROM `address` WHERE region_id IN ("
                        + placeholders + ") GROUP BY region_id",
                collect,
                regionIds.toArray());
        return map;
    }

    @Transactional
    public Region save(RegionCommand cmd) {
        String name = requireName(cmd.name());
        boolean creating = cmd.id() == null;

        Region region;
        if (creating) {
            region = new Region();
            region.setStatus("enabled");
            region.setSort(cmd.sort() == null ? 0 : cmd.sort());
            region.setCreatedAt(LocalDateTime.now());
        } else {
            region = require(cmd.id());
            if (cmd.sort() != null) {
                region.setSort(cmd.sort());
            }
        }
        region.setName(name);
        region.setRemark(trimToNull(cmd.remark()));

        try {
            if (creating) {
                regionMapper.insert(region);
                log.info("新增区域 id={} name={}", region.getId(), name);
            } else {
                regionMapper.updateById(region);
                log.info("编辑区域 id={} name={}", region.getId(), name);
            }
        } catch (DuplicateKeyException e) {
            // 靠数据库唯一索引兜底：先查再插在并发下仍可能让两个同名区域同时通过
            throw BizException.conflict("已存在同名区域「" + name + "」");
        }
        return region;
    }

    /** 启用 / 停用。停用后不再出现在「新增地址」的选择器里，且其下地址**不能再用于下单**（N-44）。 */
    @Transactional
    public Region updateStatus(Long id, String status) {
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw BizException.param("状态只能是 enabled 或 disabled");
        }
        Region region = require(id);
        region.setStatus(status);
        regionMapper.updateById(region);
        log.info("区域状态变更 id={} -> {}", id, status);
        return region;
    }

    /**
     * 删除。两个拦路条件：区域内还有地址、或已被历史订单引用。
     * 两者都只允许停用。
     */
    @Transactional
    public void delete(Long id) {
        Region region = require(id);

        Integer addressCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `address` WHERE region_id = ?", Integer.class, id);
        if (addressCount != null && addressCount > 0) {
            throw BizException.conflict("「" + region.getName() + "」下还有 " + addressCount
                    + " 个地址，只能停用不能删除");
        }

        long orderCount = orderUsageQuery.countOrdersUsingRegion(id);
        if (orderCount > 0) {
            throw BizException.conflict("「" + region.getName() + "」已被 " + orderCount
                    + " 笔历史订单引用，只能停用不能删除，否则历史备菜数据对不上");
        }

        regionMapper.deleteById(id);
        log.info("删除区域 id={} name={}", id, region.getName());
    }

    public Region require(Long id) {
        Region region = id == null ? null : regionMapper.selectById(id);
        if (region == null) {
            throw BizException.notFound("区域不存在");
        }
        return region;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw BizException.param("请填写区域名称");
        }
        if (name.length() > 64) {
            throw BizException.param("区域名称最多 64 个字");
        }
        return name;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** @param sort 编辑时传 null 表示不修改排序 */
    public record RegionCommand(Long id, String name, String remark, Integer sort) {
    }

    /**
     * 商家端区域行：实体字段 + 该区域下的地址数。
     *
     * <p>{@code addressCount} 是**实时计数**，不落库：区域停用不改地址表，
     * 地址增删、地址启停都不需要维护这个数，也就没有「计数和实际对不上」的可能。
     */
    public record RegionView(Long id, String name, String remark, Integer sort, String status,
                             LocalDateTime createdAt, int addressCount) {
    }
}

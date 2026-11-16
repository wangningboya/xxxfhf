package com.xxxfhf.meal.region;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
    private final JdbcTemplate jdbcTemplate;

    public RegionService(RegionMapper regionMapper, JdbcTemplate jdbcTemplate) {
        this.regionMapper = regionMapper;
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

    @Transactional
    public Region save(RegionCommand cmd) {
        String name = requireName(cmd.name());
        boolean creating = cmd.id() == null || cmd.id().isBlank();

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

    /** 启用 / 停用。停用后不再出现在「新增地址」的选择器里，但已挂其下的地址照常可用。 */
    @Transactional
    public Region updateStatus(String id, String status) {
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
    public void delete(String id) {
        Region region = require(id);

        Integer addressCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `address` WHERE region_id = ?", Integer.class, id);
        if (addressCount != null && addressCount > 0) {
            throw BizException.conflict("「" + region.getName() + "」下还有 " + addressCount
                    + " 个地址，只能停用不能删除");
        }

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE region_id = ?", Integer.class, id);
        if (orderCount != null && orderCount > 0) {
            throw BizException.conflict("「" + region.getName() + "」已被 " + orderCount
                    + " 笔历史订单引用，只能停用不能删除，否则历史备菜数据对不上");
        }

        regionMapper.deleteById(id);
        log.info("删除区域 id={} name={}", id, region.getName());
    }

    public Region require(String id) {
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
    public record RegionCommand(String id, String name, String remark, Integer sort) {
    }
}

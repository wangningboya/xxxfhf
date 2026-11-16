package com.xxxfhf.meal.address;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.PhoneValidator;
import com.xxxfhf.meal.region.Region;
import com.xxxfhf.meal.region.RegionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址管理。
 *
 * 两条路，区别只在**谁能立刻用**：
 * - 商家录入 → `active`，立刻可下单
 * - 用户自己新增 / 修改 → `pending`，商家确认后才出现在可选用列表里
 *
 * 为什么用户改自己的地址也要待审：地址是配送依据，写错了整单送不到；
 * 由商家过一眼的成本远低于中午发现地址不存在再打电话。
 */
@Service
public class AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressService.class);

    private final AddressMapper addressMapper;
    private final RegionService regionService;

    public AddressService(AddressMapper addressMapper, RegionService regionService) {
        this.addressMapper = addressMapper;
        this.regionService = regionService;
    }

    /** 某个用户的全部地址（含待确认的），用户端与商家端都用这一个查询。 */
    public List<Address> listByUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw BizException.param("缺少用户 ID");
        }
        return addressMapper.selectList(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId)
                .orderByAsc(Address::getStatus)
                .orderByAsc(Address::getRegionName));
    }

    /** 商家录入 / 修改：直接可用。 */
    @Transactional
    public Address saveByMerchant(AddressCommand cmd) {
        if (cmd.userId() == null || cmd.userId().isBlank()) {
            throw BizException.param("缺少归属用户");
        }
        Address address = cmd.id() == null || cmd.id().isBlank() ? new Address() : require(cmd.id());
        if (address.getId() != null && !cmd.userId().equals(address.getUserId())) {
            throw BizException.param("不能把地址改到其他用户名下");
        }
        address.setUserId(cmd.userId());
        fill(address, cmd);
        address.setSource("merchant");
        address.setStatus("active");

        if (address.getId() == null) {
            addressMapper.insert(address);
            log.info("商家新增地址 id={} userId={}", address.getId(), cmd.userId());
        } else {
            addressMapper.updateById(address);
            log.info("商家修改地址 id={} userId={}", address.getId(), cmd.userId());
        }
        return address;
    }

    /**
     * 用户自己新增 / 修改：落 `pending`，等商家确认。
     *
     * 修改也回到 pending —— 改完地址若立刻生效，等于绕过了上面说的那一眼。
     */
    @Transactional
    public Address saveByUser(String userId, AddressCommand cmd) {
        boolean creating = cmd.id() == null || cmd.id().isBlank();
        Address address;
        if (creating) {
            address = new Address();
        } else {
            address = require(cmd.id());
            if (!userId.equals(address.getUserId())) {
                throw BizException.forbidden("只能修改自己的地址");
            }
        }
        address.setUserId(userId);
        fill(address, cmd);
        address.setSource("user");
        address.setStatus("pending");

        if (creating) {
            addressMapper.insert(address);
            log.info("用户新增地址 id={} userId={}（待确认）", address.getId(), userId);
        } else {
            addressMapper.updateById(address);
            log.info("用户修改地址 id={} userId={}（重新待确认）", address.getId(), userId);
        }
        return address;
    }

    /** 商家确认 / 收回地址（确认后用户才能在下单时选到它）。 */
    @Transactional
    public Address updateStatus(String addressId, String status) {
        if (!"active".equals(status) && !"pending".equals(status)) {
            throw BizException.param("状态只能是 active 或 pending");
        }
        Address address = require(addressId);
        address.setStatus(status);
        addressMapper.updateById(address);
        log.info("地址状态变更 id={} -> {}", addressId, status);
        return address;
    }

    /** 删除。历史订单存的是地址快照，删地址不影响已下的单。 */
    @Transactional
    public void delete(String addressId) {
        require(addressId);
        addressMapper.deleteById(addressId);
        log.info("删除地址 id={}", addressId);
    }

    public Address require(String id) {
        Address address = id == null ? null : addressMapper.selectById(id);
        if (address == null) {
            throw BizException.notFound("地址不存在");
        }
        return address;
    }

    /** 字段填充与校验。区域必填且必须存在、启用。 */
    private void fill(Address address, AddressCommand cmd) {
        String regionId = cmd.regionId() == null ? "" : cmd.regionId().trim();
        if (regionId.isEmpty()) {
            throw BizException.param("请选择所属区域");
        }
        Region region = regionService.require(regionId);
        // 已停用的区域不能再挂新地址；但地址原本就挂在这个区域上的（比如只是改个门牌）不拦
        if ("disabled".equals(region.getStatus()) && !regionId.equals(address.getRegionId())) {
            throw BizException.param("「" + region.getName() + "」已停用，不能新增该区域的地址");
        }

        String contactName = cmd.contactName() == null ? "" : cmd.contactName().trim();
        if (contactName.isEmpty()) {
            throw BizException.param("请填写收货人");
        }
        String detail = cmd.detail() == null ? "" : cmd.detail().trim();
        if (detail.isEmpty()) {
            throw BizException.param("请填写详细地址");
        }

        address.setRegionId(regionId);
        // 区域名称写冗余列：区域改名后老地址仍显示下单时的名字口径，
        // 与订单快照保持一致，不会出现「同一个地址在两个地方名字不一样」
        address.setRegionName(region.getName());
        address.setContactName(contactName);
        address.setDetail(detail);
        address.setPhone(PhoneValidator.optional(cmd.phone()));
        address.setTag(trimToNull(cmd.tag()));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 保存入参。
     *
     * @param id 为空 = 新增；非空 = 修改
     * @param phone 可选（自取单不需要手机号），填了才校验格式
     */
    public record AddressCommand(String id, String userId, String regionId, String contactName,
                                 String phone, String detail, String tag) {
    }
}

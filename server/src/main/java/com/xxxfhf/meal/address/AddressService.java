package com.xxxfhf.meal.address;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ContentSecurityService;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.common.PageResult;
import com.xxxfhf.meal.common.PhoneValidator;
import com.xxxfhf.meal.region.Region;
import com.xxxfhf.meal.region.RegionService;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final ContentSecurityService contentSecurity;

    public AddressService(AddressMapper addressMapper, RegionService regionService,
                          UserMapper userMapper, AuditService auditService,
                          ContentSecurityService contentSecurity) {
        this.addressMapper = addressMapper;
        this.regionService = regionService;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.contentSecurity = contentSecurity;
    }

    /** 某个用户的全部地址（含待确认的），用户端与商家端都用这一个查询。 */
    public List<Address> listByUser(Long userId) {
        if (userId == null) {
            throw BizException.param("缺少用户 ID");
        }
        return addressMapper.selectList(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId)
                .orderByAsc(Address::getStatus)
                .orderByAsc(Address::getRegionName));
    }

    /**
     * 商家端地址队列（跨用户），默认看待确认。
     *
     * 为什么不再以「选用户」为入口：工作台那个「待确认地址」数字本来就是全库口径，
     * 点进来却落在一个必须先选人的空页面上，等于让人自己去猜该看谁。地址审核天然是队列。
     *
     * @param status   active / pending / rejected，空则全部
     * @param userId   只看某个用户的（原来的用法，现在降级成筛选条件）
     * @param keyword  联系人 / 手机号 / 详细地址模糊匹配
     */
    public PageResult<AddressRow> page(String status, Long userId, Long regionId, String keyword,
                                       int page, int pageSize) {
        String kw = keyword == null ? "" : keyword.trim();
        // 待确认队列要「先来的先审」，所以正序；其余页签看最近发生的，倒序。
        // 片段是常量，不含用户输入。
        boolean pendingQueue = "pending".equals(status);
        String orderBy = pendingQueue
                ? "ORDER BY created_at ASC"
                : "ORDER BY FIELD(status, 'pending', 'rejected', 'active'), created_at DESC";

        Page<Address> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        IPage<Address> result = addressMapper.selectPage(pager, Wrappers.<Address>lambdaQuery()
                .eq(status != null && !status.isBlank(), Address::getStatus, status)
                .eq(userId != null, Address::getUserId, userId)
                .eq(regionId != null, Address::getRegionId, regionId)
                .and(!kw.isEmpty(), w -> w.like(Address::getContactName, kw)
                        .or().like(Address::getPhone, kw)
                        .or().like(Address::getDetail, kw))
                .last(orderBy));

        Map<Long, User> users = usersOf(result.getRecords());
        List<AddressRow> rows = new ArrayList<>(result.getRecords().size());
        for (Address address : result.getRecords()) {
            User user = users.get(address.getUserId());
            rows.add(new AddressRow(address.getId(), address.getUserId(),
                    user == null ? null : user.getNickName(),
                    // 账号手机号只做「这个地址是谁的」参考，给掩码；
                    // 收货电话（address.phone）保留明文：商家要真拨过去，这是配送履约的必需数据
                    user == null ? null : MaskUtil.phone(user.getPhone()),
                    user == null ? null : user.getAuditStatus(),
                    address.getRegionId(), address.getRegionName(), address.getContactName(),
                    address.getPhone(), address.getDetail(), address.getTag(),
                    address.getSource(), address.getStatus(), address.getRejectReason(),
                    address.getCreatedAt()));
        }
        return new PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 一次查完本页涉及的账号，避免逐行查用户表 */
    private Map<Long, User> usersOf(List<Address> addresses) {
        if (addresses.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = addresses.stream().map(Address::getUserId).distinct().toList();
        Map<Long, User> map = new HashMap<>(ids.size());
        for (User user : userMapper.selectBatchIds(ids)) {
            map.put(user.getId(), user);
        }
        return map;
    }

    /** 商家录入 / 修改：直接可用。 */
    @Transactional
    public Address saveByMerchant(AddressCommand cmd) {
        if (cmd.userId() == null) {
            throw BizException.param("缺少归属用户");
        }
        Address address = cmd.id() == null ? new Address() : require(cmd.id());
        if (address.getId() != null && !cmd.userId().equals(address.getUserId())) {
            throw BizException.param("不能把地址改到其他用户名下");
        }
        address.setUserId(cmd.userId());
        fill(address, cmd, cmd.userId());
        address.setSource("merchant");
        address.setStatus("active");

        if (address.getId() == null) {
            addressMapper.insert(address);
            log.info("商家新增地址 id={} userId={}", address.getId(), cmd.userId());
            auditService.record(AuditActions.TABLE_ADDRESS, address.getId(), AuditActions.ADDRESS_CREATE,
                    "商家新增地址：" + describe(address));
        } else {
            addressMapper.updateById(address);
            clearRejectReason(address.getId());
            log.info("商家修改地址 id={} userId={}", address.getId(), cmd.userId());
            auditService.record(AuditActions.TABLE_ADDRESS, address.getId(), AuditActions.ADDRESS_UPDATE,
                    "商家修改地址：" + describe(address));
        }
        return address;
    }

    /**
     * 用户自己新增 / 修改：落 `pending`，等商家确认。
     *
     * 修改也回到 pending —— 改完地址若立刻生效，等于绕过了上面说的那一眼。
     */
    @Transactional
    public Address saveByUser(Long userId, AddressCommand cmd) {
        boolean creating = cmd.id() == null;
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
        fill(address, cmd, userId);
        address.setSource("user");
        address.setStatus("pending");

        if (creating) {
            addressMapper.insert(address);
            log.info("用户新增地址 id={} userId={}（待确认）", address.getId(), userId);
            auditService.record(AuditActions.TABLE_ADDRESS, address.getId(), AuditActions.ADDRESS_CREATE,
                    "用户新增地址（待确认）：" + describe(address));
        } else {
            addressMapper.updateById(address);
            clearRejectReason(address.getId());
            log.info("用户修改地址 id={} userId={}（重新待确认）", address.getId(), userId);
            // 用户把地址改到别的区域时，商家必须能在流水里看到 —— 配送范围是商家的判断，不是用户的
            auditService.record(AuditActions.TABLE_ADDRESS, address.getId(), AuditActions.ADDRESS_UPDATE,
                    "用户修改地址（重新待确认）：" + describe(address));
        }
        return address;
    }

    /**
     * 清掉历史驳回原因。
     *
     * 必须单独走一次条件更新：`updateById` 会跳过 null 字段，
     * 只把 status 改回 pending 的话，上一次的驳回原因会一直挂在地址上。
     */
    private void clearRejectReason(Long addressId) {
        if (addressId == null) {
            return;
        }
        addressMapper.update(null, AuditHelper.stamp(Wrappers.<Address>lambdaUpdate()
                .set(Address::getRejectReason, null)
                .eq(Address::getId, addressId)));
    }

    /**
     * 商家确认 / 驳回 / 收回地址。
     *
     * 驳回必须给原因：用户端要把「哪里不对」显示出来，否则他只能反复提交同一个错误地址。
     * 三种状态之外一律拒绝 —— 地址状态直接决定这单能不能下，不能出现未定义值。
     */
    @Transactional
    public Address updateStatus(Long addressId, String status, String reason) {
        String target = status == null ? "" : status.trim();
        if (!"active".equals(target) && !"pending".equals(target) && !"rejected".equals(target)) {
            throw BizException.param("状态只能是 active、pending 或 rejected");
        }
        Address address = require(addressId);

        String trimmed = reason == null ? "" : reason.trim();
        if ("rejected".equals(target)) {
            if (trimmed.isEmpty()) {
                throw BizException.param("驳回地址必须填写原因，用户需要知道改哪里");
            }
            if (trimmed.length() > 200) {
                throw BizException.param("驳回原因最多 200 字");
            }
        }
        // 用条件更新而不是 updateById：updateById 会跳过 null 字段，
        // 导致「从驳回改回可用」时旧原因清不掉，用户端一直挂着一句过期的话
        addressMapper.update(null, AuditHelper.stamp(Wrappers.<Address>lambdaUpdate()
                .set(Address::getStatus, target)
                .set(Address::getRejectReason, "rejected".equals(target) ? trimmed : null)
                .eq(Address::getId, addressId)));

        address.setStatus(target);
        address.setRejectReason("rejected".equals(target) ? trimmed : null);
        log.info("地址状态变更 id={} -> {} 原因={}", addressId, target, trimmed);

        String action = switch (target) {
            case "active" -> AuditActions.ADDRESS_APPROVE;
            case "rejected" -> AuditActions.ADDRESS_REJECT;
            default -> AuditActions.ADDRESS_REVOKE;
        };
        String verb = switch (target) {
            case "active" -> "通过地址";
            case "rejected" -> "驳回地址";
            default -> "收回地址为待确认";
        };
        auditService.record(AuditActions.TABLE_ADDRESS, addressId, action,
                verb + "：" + describe(address)
                        + ("rejected".equals(target) ? "｜原因：" + trimmed : ""));
        return address;
    }

    /** 删除。历史订单存的是地址快照，删地址不影响已下的单。 */
    @Transactional
    public void delete(Long addressId) {
        Address address = require(addressId);
        addressMapper.deleteById(addressId);
        log.info("删除地址 id={}", addressId);
        // 描述要在删除前取：删完对象还在内存里，但这一行已经查不回来了
        auditService.record(AuditActions.TABLE_ADDRESS, addressId, AuditActions.ADDRESS_DELETE,
                "删除地址：" + describe(address));
    }

    public Address require(Long id) {
        Address address = id == null ? null : addressMapper.selectById(id);
        if (address == null) {
            throw BizException.notFound("地址不存在");
        }
        return address;
    }

    /** 字段填充与校验。区域必填且必须存在、启用。 */
    private void fill(Address address, AddressCommand cmd, Long userId) {
        Long regionId = cmd.regionId();
        if (regionId == null) {
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
        requireContentOk(userId, contactName, "收货人姓名包含违规内容，请修改");
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
        address.setPhone(PhoneValidator.require(cmd.phone()));
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
     * 收货人姓名先过内容安全。
     *
     * <p>它会被拼进配送单与备菜表，传播面比昵称大；只有命中违规才拒，
     * 检测接口不可用时放行（熟人小店，挡住正常用户比漏一句话更糟）。
     */
    private void requireContentOk(Long userId, String content, String message) {
        if (content == null) {
            return;
        }
        User user = userId == null ? null : userMapper.selectById(userId);
        String openid = user == null ? null : user.getOpenid();
        if (!contentSecurity.pass(userId, openid, content)) {
            throw BizException.param(message);
        }
    }

    /** 操作记录里指代一条地址：区域 + 门牌 + 联系人，够认出是哪条 */
    private static String describe(Address address) {
        StringBuilder text = new StringBuilder();
        if (address.getRegionName() != null) {
            text.append(address.getRegionName()).append(' ');
        }
        if (address.getDetail() != null) {
            text.append(address.getDetail());
        }
        if (address.getContactName() != null) {
            text.append('（').append(address.getContactName());
            if (address.getPhone() != null) {
                text.append(' ').append(address.getPhone());
            }
            text.append('）');
        }
        return text.toString();
    }

    /**
     * 地址队列行：地址本体 + 归属账号。
     *
     * 不直接把实体塞进响应：表格要看「这是谁提的」，而实体上没有昵称；
     * 同时审计四列对前端隐藏，提交时间只能由这里显式带出来。
     */
    public record AddressRow(Long id, Long userId, String userName, String userPhone,
                             String userAuditStatus, Long regionId, String regionName,
                             String contactName, String phone, String detail, String tag,
                             String source, String status, String rejectReason,
                             LocalDateTime createdAt) {
    }

    /**
     * 保存入参。
     *
     * @param id 为空 = 新增；非空 = 修改
     * @param phone 必填（需求 4.8：配送送达后要能联系到人），11 位大陆手机号
     */
    public record AddressCommand(Long id, Long userId, Long regionId, String contactName,
                                 String phone, String detail, String tag) {
    }
}

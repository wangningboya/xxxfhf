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
import com.xxxfhf.meal.message.MessageService;
import com.xxxfhf.meal.message.NoticeType;
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
import java.util.Collections;
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
    private final MessageService messageService;

    public AddressService(AddressMapper addressMapper, RegionService regionService,
                          UserMapper userMapper, AuditService auditService,
                          ContentSecurityService contentSecurity, MessageService messageService) {
        this.addressMapper = addressMapper;
        this.regionService = regionService;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.contentSecurity = contentSecurity;
        this.messageService = messageService;
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
     * 用户端「我的地址」列表：实体字段 + 服务端算好的可用性（N-44）。
     *
     * <p>为什么可用性要服务端算：能不能下单取决于三处（审核状态 / 地址启用 / 区域启用）。
     * 这三个判断原先散落在地址列表页、确认订单页和下单接口里各写一遍，
     * 改一处漏一处就会出现「页面能点、提交被打回」。现在只有一个实现点（见 {@link #usabilityOf}），
     * 前端只负责把 `usable = false` 的置灰、把 `unusableReason` 显示出来。
     */
    public List<AddressView> listViewsByUser(Long userId) {
        List<Address> addresses = listByUser(userId);
        Map<Long, String> regionStatuses = regionService.statusesOf(
                addresses.stream().map(Address::getRegionId).toList());
        List<AddressView> views = new ArrayList<>(addresses.size());
        for (Address address : addresses) {
            views.add(viewOf(address, regionStatusOf(regionStatuses, address.getRegionId())));
        }
        return views;
    }

    private static AddressView viewOf(Address address, String regionStatus) {
        Usability usability = usabilityOf(address, regionStatus);
        boolean enabled = isEnabled(address);
        // 编辑入口只对「两侧都启用」的地址开放。停用的地址让用户改没有意义，
        // 更糟的是他能把区域换掉来绕过停用（D-34）；待确认 / 已驳回仍可改，那是审核流程的一部分
        boolean editable = enabled && "enabled".equals(regionStatus);
        return new AddressView(address.getId(), address.getRegionId(), address.getRegionName(),
                address.getContactName(), address.getPhone(), address.getDetail(), address.getTag(),
                address.getSource(), address.getStatus(), address.getRejectReason(),
                enabled, address.getDisabledReason(), regionStatus,
                usability.usable(), usability.label(), editable);
    }

    private static String regionStatusOf(Map<Long, String> statuses, Long regionId) {
        // 查不到当作启用：区域被物理删除后遗留的地址不该让整页列表报错，可用性另有兜底
        String status = statuses.get(regionId);
        return status == null ? "enabled" : status;
    }

    /**
     * 一条地址能不能用于下单，以及不能时的文案。
     *
     * <p>顺序固定：**审核状态 → 地址启用 → 区域启用**。先审核是因为它决定「这条地址算不算数」，
     * 一个还没通过的地址连停用与否都谈不上；区域放最后是因为它是最外层的开关，
     * 商家停用区域时看到的原因应该是「区域已停用」，而不是底下每条的各自原因。
     *
     * @param regionStatus 该地址所属区域的状态，由调用方批量查好传进来（避免逐条查库）
     */
    public static Usability usabilityOf(Address address, String regionStatus) {
        if (!"active".equals(address.getStatus())) {
            if ("rejected".equals(address.getStatus())) {
                String reason = trimToNull(address.getRejectReason());
                return new Usability(false,
                        reason == null ? "已驳回" : "已驳回：" + reason,
                        "该地址已被驳回，请修改或重新添加");
            }
            return new Usability(false, "待商家确认", "该地址还在等待商家确认，暂时不能用于下单");
        }
        if (!isEnabled(address)) {
            String reason = trimToNull(address.getDisabledReason());
            return new Usability(false,
                    reason == null ? "已停用" : "已停用：" + reason,
                    "该地址已被商家停用，暂时不能用于下单");
        }
        if ("disabled".equals(regionStatus)) {
            return new Usability(false, "所属区域已停用", "该地址所在区域已停用，暂时不能配送到该区域");
        }
        return new Usability(true, null, null);
    }

    /** 库里默认 1，Java 侧可能是 null（老数据 / 未回填），一律当作启用 */
    private static boolean isEnabled(Address address) {
        return !Boolean.FALSE.equals(address.getEnabled());
    }

    /**
     * 下单前的地址校验（N-44）：存在性、归属照旧硬校验，可用性可被 {@code force} 跳过。
     *
     * @param force 只有代客下单传 true —— 商家在电话里已经和顾客确认过地址，
     *              拦死等于否定线下判断。它只跳过可用性，
     *              「地址不存在」「不是本人的地址」这类仍然拒
     */
    public AddressCheck requireUsableForOrder(Long addressId, Long userId, boolean force) {
        Address address = require(addressId);
        if (userId != null && !userId.equals(address.getUserId())) {
            throw BizException.forbidden("只能使用自己的收货地址");
        }
        Map<Long, String> statuses = regionService.statusesOf(
                Collections.singletonList(address.getRegionId()));
        Usability usability = usabilityOf(address, regionStatusOf(statuses, address.getRegionId()));
        if (!usability.usable() && !force) {
            throw BizException.conflict(usability.message());
        }
        return new AddressCheck(address, usability);
    }

    /**
     * 商家端地址队列（跨用户），默认看待确认。
     *
     * 为什么不再以「选用户」为入口：工作台那个「待确认地址」数字本来就是全库口径，
     * 点进来却落在一个必须先选人的空页面上，等于让人自己去猜该看谁。地址审核天然是队列。
     *
     * @param status   active / pending / rejected，空则全部
     * @param enabled  1 只看启用 / 0 只看停用 / 空则全部（N-44）
     * @param userId   只看某个用户的（原来的用法，现在降级成筛选条件）
     * @param keyword  联系人 / 手机号 / 详细地址模糊匹配
     */
    public PageResult<AddressRow> page(String status, Integer enabled, Long userId, Long regionId,
                                       String keyword, int page, int pageSize) {
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
                .eq(enabled != null, Address::getEnabled, enabled)
                .eq(userId != null, Address::getUserId, userId)
                .eq(regionId != null, Address::getRegionId, regionId)
                .and(!kw.isEmpty(), w -> w.like(Address::getContactName, kw)
                        .or().like(Address::getPhone, kw)
                        .or().like(Address::getDetail, kw))
                .last(orderBy));

        Map<Long, User> users = usersOf(result.getRecords());
        Map<Long, String> regionStatuses = regionService.statusesOf(
                result.getRecords().stream().map(Address::getRegionId).toList());
        List<AddressRow> rows = new ArrayList<>(result.getRecords().size());
        for (Address address : result.getRecords()) {
            User user = users.get(address.getUserId());
            String regionStatus = regionStatusOf(regionStatuses, address.getRegionId());
            rows.add(new AddressRow(address.getId(), address.getUserId(),
                    user == null ? null : user.getNickName(),
                    // 账号手机号只做「这个地址是谁的」参考，给掩码；
                    // 收货电话（address.phone）保留明文：商家要真拨过去，这是配送履约的必需数据
                    user == null ? null : MaskUtil.phone(user.getPhone()),
                    user == null ? null : user.getAuditStatus(),
                    address.getRegionId(), address.getRegionName(), address.getContactName(),
                    address.getPhone(), address.getDetail(), address.getTag(),
                    address.getSource(), address.getStatus(), address.getRejectReason(),
                    address.getCreatedAt(),
                    isEnabled(address), address.getDisabledReason(), regionStatus,
                    usabilityOf(address, regionStatus).usable()));
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

    /**
     * 地址页专用的用户搜索（筛选下拉与代客新增共用）：customer 与 merchant 都能搜到。
     *
     * <p>为什么不用 /admin/user/list：那份列表只出群成员（user_type = customer）。
     * 「指定为商家」是就地升级（D-26），同一行记录的类型改成 merchant 后，
     * 这个人就从群成员列表里消失了 —— 但他的地址、订单、openid 都还挂在同一条记录上，
     * 地址维护理应照常选得到他。
     *
     * <p>不过滤审核状态：被停用 / 驳回过的用户，名下既有地址仍要在列表里筛得到。
     */
    public PageResult<UserOption> pageUsers(String keyword, int page, int pageSize) {
        String kw = keyword == null ? "" : keyword.trim();
        Page<User> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        IPage<User> result = userMapper.selectPage(pager, Wrappers.<User>lambdaQuery()
                .and(!kw.isEmpty(), w -> w.like(User::getNickName, kw).or().like(User::getPhone, kw))
                .last("ORDER BY created_at DESC"));

        List<UserOption> rows = new ArrayList<>(result.getRecords().size());
        for (User user : result.getRecords()) {
            rows.add(new UserOption(user.getId(), user.getNickName(),
                    MaskUtil.phone(user.getPhone()), user.getUserType()));
        }
        return new PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
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
        if (address.getId() == null) {
            // 新建的地址一律启用；停用是商家对既有地址的处置，不是新建时的选项
            address.setEnabled(true);
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
            address.setEnabled(true);
        } else {
            address = require(cmd.id());
            if (!userId.equals(address.getUserId())) {
                throw BizException.forbidden("只能修改自己的地址");
            }
            // 停用的地址不给用户改（D-34）。只在前端把编辑入口藏起来是不够的：
            // 改 URL 传一个 id 就能提交，而「把地址换到别的区域」正好绕开区域停用。
            // 但待确认 / 已驳回仍然放行 —— 驳回后用户必须能改完重新提交，那是审核流程本身
            if (!isEnabled(address)) {
                throw BizException.conflict("该地址已被商家停用，暂时不能修改");
            }
            String regionStatus = regionStatusOf(
                    regionService.statusesOf(Collections.singletonList(address.getRegionId())),
                    address.getRegionId());
            if ("disabled".equals(regionStatus)) {
                throw BizException.conflict("该地址所在区域已停用，暂时不能修改");
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
        // 驳回与收回都要告诉归属用户（v3.50）：地址没了他到下单那一刻才发现，
        // 等于把商家的一次审核变成用户的一次失败。只落站内消息
        if ("rejected".equals(target)) {
            messageService.recordToUser(address.getUserId(), NoticeType.ADDRESS_REJECTED,
                    "地址未通过", "你提交的地址「" + describeShort(address) + "」未通过商家确认，原因："
                            + trimmed + "。请修改后重新提交");
        } else if ("pending".equals(target)) {
            messageService.recordToUser(address.getUserId(), NoticeType.ADDRESS_REVOKED,
                    "地址已停用", "地址「" + describeShort(address)
                            + "」已被商家收回，需重新确认后才能用于下单");
        }
        return address;
    }

    /**
     * 地址启用 / 停用（N-44）。与审核状态正交：不动 `status`，只开关 {@code enabled}。
     *
     * <p>**不校验区域状态**：区域停用时照样允许商家先处置地址，否则「区域因装修停一周」
     * 期间商家什么都不能整理，只能等区域恢复。
     *
     * <p>启用时把停用原因一起清掉。`updateById` 会跳过 null 字段，
     * 所以必须用一次条件更新同时写 `enabled` 与 `disabled_reason`，
     * 否则上次的原因会挂在一条已启用的地址上 —— 用户端就会看到「已停用：装修」却又能下单。
     *
     * @param reason 停用原因，可选填，≤200 字；启用时忽略
     */
    @Transactional
    public Address updateEnabled(Long addressId, boolean enabled, String reason) {
        Address address = require(addressId);
        String trimmed = trimToNull(reason);
        if (trimmed != null && trimmed.length() > 200) {
            throw BizException.param("停用原因最多 200 字");
        }
        String target = enabled ? null : trimmed;

        addressMapper.update(null, AuditHelper.stamp(Wrappers.<Address>lambdaUpdate()
                .set(Address::getEnabled, enabled)
                .set(Address::getDisabledReason, target)
                .eq(Address::getId, addressId)));

        address.setEnabled(enabled);
        address.setDisabledReason(target);
        log.info("地址启用状态变更 id={} enabled={} 原因={}", addressId, enabled, target);

        String action = enabled ? AuditActions.ADDRESS_ENABLE : AuditActions.ADDRESS_DISABLE;
        String verb = enabled ? "启用地址" : "停用地址";
        auditService.record(AuditActions.TABLE_ADDRESS, addressId, action,
                verb + "：" + describe(address) + (target == null ? "" : "｜原因：" + target));
        // 停用也要说一声：用户看到地址还在列表里，却下不了单，会比「被驳回」更困惑
        if (!enabled) {
            messageService.recordToUser(address.getUserId(), NoticeType.ADDRESS_REVOKED,
                    "地址已停用", "地址「" + describeShort(address) + "」已被商家停用"
                            + (target == null ? "" : "，原因：" + target) + "，暂不能用于下单");
        }
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
     * 给用户看的地址短描述：区域 + 门牌，不带联系人手机号。
     *
     * <p>用户消息里没必要重复他自己的电话，写长了反而会把 {@code VARCHAR(255)} 撑满，
     * 把真正要说的「为什么没通过」挤掉。
     */
    private static String describeShort(Address address) {
        String region = address.getRegionName() == null ? "" : address.getRegionName();
        String detail = address.getDetail() == null ? "" : address.getDetail();
        String text = (region + " " + detail).trim();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    /**
     * 地址页用户搜索行：下拉展示（昵称 / 掩码手机号）+ 归属回填所需的最小字段。
     *
     * @param userType customer / merchant。前端据此决定要不要去查完整手机号 ——
     *                 手机号明文接口（user.phone.view）只认 customer，
     *                 对 merchant 发请求必然 400，还会经全局拦截器弹出错误提示
     */
    public record UserOption(Long userId, String nickName, String phone, String userType) {
    }

    /**
     * 地址队列行：地址本体 + 归属账号 + 可用性判定结果。
     *
     * 不直接把实体塞进响应：表格要看「这是谁提的」，而实体上没有昵称；
     * 同时审计四列对前端隐藏，提交时间只能由这里显式带出来。
     *
     * @param enabled        地址自身的启用状态（N-44）
     * @param disabledReason 停用原因，启用时为 null
     * @param regionStatus   所属区域状态；区域停用时这里为 disabled，而 enabled 可能仍是 true
     * @param usable         三个条件相乘后的结论，前端直接用它决定标签颜色，不再自己拼判断
     */
    public record AddressRow(Long id, Long userId, String userName, String userPhone,
                             String userAuditStatus, Long regionId, String regionName,
                             String contactName, String phone, String detail, String tag,
                             String source, String status, String rejectReason,
                             LocalDateTime createdAt,
                             boolean enabled, String disabledReason, String regionStatus,
                             boolean usable) {
    }

    /**
     * 用户端地址视图：实体字段 + 服务端算好的可用性（N-44）。
     *
     * @param usable         能不能用于下单
     * @param unusableReason 不能时的原因，可用时为 null。如「已停用：装修」「所属区域已停用」
     * @param editable       能不能改：地址停用或区域停用都不许改（D-34），
     *                       待确认 / 已驳回可以改（改了回 pending 等商家重新确认）
     */
    public record AddressView(Long id, Long regionId, String regionName, String contactName,
                              String phone, String detail, String tag, String source,
                              String status, String rejectReason, boolean enabled,
                              String disabledReason, String regionStatus, boolean usable,
                              String unusableReason, boolean editable) {
    }

    /**
     * 可用性判定结果。
     *
     * @param label   给用户看的一句话，直接显示在地址卡片上；可用时为 null
     * @param message 不能下单时抛出去的 409 文案；可用时为 null
     */
    public record Usability(boolean usable, String label, String message) {
    }

    /** 下单前校验结果：地址本体 + 判定结论（force 放行时 usable 仍为 false，调用方据此留痕） */
    public record AddressCheck(Address address, Usability usability) {
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

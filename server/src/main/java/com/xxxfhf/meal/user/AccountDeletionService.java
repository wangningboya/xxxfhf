package com.xxxfhf.meal.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.address.Address;
import com.xxxfhf.meal.address.AddressMapper;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.MaskUtil;
import com.xxxfhf.meal.order.AddressSnapshot;
import com.xxxfhf.meal.order.Order;
import com.xxxfhf.meal.order.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户自助注销（删除权）。
 *
 * <p>规则只有两条，都是为了「删得掉，但账还对得上」：
 * <ol>
 *   <li><b>没下过单的，整行删掉</b>：账号、地址一起清，什么都不留；</li>
 *   <li><b>下过单的，匿名化而不是删行</b>：订单里的 {@code user_id} 是交易凭证的一部分，
 *       删用户会让对账与售后断链。所以保留行，把昵称改「已注销」、手机号 / openid / 头像置空、
 *       状态转为停用、令牌版本 +1（当场踢下线），历史订单快照里的电话改成掩码。</li>
 * </ol>
 *
 * <p><b>为什么不在注销时删订单</b>：订单是《电子商务法》要求留存三年的交易记录，
 * 且用户可能仍在退款 / 售后窗口内。保留 + 脱敏是唯一能同时满足两条约束的做法。
 *
 * <p><b>为什么用 update wrapper 而不是 updateById</b>：MyBatis-Plus 的 {@code updateById}
 * 默认忽略 null 字段，置空动作会被安静地吞掉 —— 那正好是这里最关键的几个字段。
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    /** 有订单：保留行、抹掉身份，订单里的电话改掩码 */
    public static final String MODE_ANONYMIZE = "anonymize";
    /** 无订单：整行删除 */
    public static final String MODE_DELETE = "delete";

    /** 注销后统一显示的昵称；订单与时间轴里仍能看出「这是一笔已注销用户的单」 */
    public static final String ANONYMOUS_NICK_NAME = "已注销";

    static final String RETENTION_NOTE = "订单会保留 3 年用于交易与售后，其中的联系电话会改成掩码；账号信息立即清除且不可恢复。";

    private final UserMapper userMapper;
    private final AddressMapper addressMapper;
    private final OrderMapper orderMapper;
    private final AuditService auditService;

    public AccountDeletionService(UserMapper userMapper, AddressMapper addressMapper,
                                  OrderMapper orderMapper, AuditService auditService) {
        this.userMapper = userMapper;
        this.addressMapper = addressMapper;
        this.orderMapper = orderMapper;
        this.auditService = auditService;
    }

    /** 注销前的告知：会删什么、会留什么。页面据此展示后果，用户确认后才调 {@link #delete}。 */
    public DeletionPreview preview(Long userId) {
        User user = requireCustomer(userId);
        long orders = countOrders(userId);
        long addresses = countAddresses(userId);
        return new DeletionPreview(orders, addresses,
                orders > 0 ? MODE_ANONYMIZE : MODE_DELETE,
                user.getNickName(), RETENTION_NOTE);
    }

    /**
     * 执行注销。
     *
     * @return 走了哪条分支、保留了几笔订单、删掉几条地址
     */
    @Transactional
    public DeletionResult delete(Long userId) {
        User user = requireCustomer(userId);
        long orders = countOrders(userId);

        // 地址先删：两种分支都不需要再保留收货地址
        int addresses = addressMapper.delete(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId));

        if (orders == 0) {
            userMapper.deleteById(userId);
            log.info("用户自助注销（无订单，账号已删除）userId={} 昵称={} 连带删地址={}",
                    userId, UserAuditService.displayName(user), addresses);
            auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_SELF_DELETE,
                    "用户自助注销：" + UserAuditService.displayName(user)
                            + "｜账号 …" + UserAuditService.idTail(userId)
                            + "｜无订单，账号已删除｜连带删地址 " + addresses + " 条");
            return new DeletionResult(MODE_DELETE, 0, addresses);
        }

        int maskedOrders = maskSnapshotPhones(userId);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getNickName, ANONYMOUS_NICK_NAME)
                .set(User::getAvatarUrl, null)
                .set(User::getPhone, null)
                .set(User::getOpenid, null)
                .set(User::getGroupOpenId, null)
                .set(User::getRejectReason, null)
                .set(User::getAuditStatus, AuditStatus.DISABLED)
                .set(User::getTokenVersion,
                        (user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1));

        log.info("用户自助注销（有订单，账号已匿名化）userId={} 昵称={} 订单={} 脱敏快照={} 连带删地址={}",
                userId, UserAuditService.displayName(user), orders, maskedOrders, addresses);
        auditService.record(AuditActions.TABLE_USER, userId, AuditActions.USER_SELF_DELETE,
                "用户自助注销（账号匿名化）：" + UserAuditService.displayName(user)
                        + "｜账号 …" + UserAuditService.idTail(userId)
                        + "｜保留订单 " + orders + " 笔（电话已脱敏）｜连带删地址 " + addresses + " 条");
        return new DeletionResult(MODE_ANONYMIZE, orders, addresses);
    }

    /**
     * 把历史订单快照里的电话改成前 3 后 2。
     *
     * <p>这里刻意用「重新 new 一个快照再 updateById」而不是 update wrapper 的 set：
     * JSON 列要靠字段上声明的 typeHandler 序列化，走 updateById 才会用上它。
     */
    private int maskSnapshotPhones(Long userId) {
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .isNotNull(Order::getAddressSnapshot)
                .select(Order::getId, Order::getAddressSnapshot));
        int masked = 0;
        for (Order order : orders) {
            AddressSnapshot snapshot = order.getAddressSnapshot();
            if (snapshot == null || snapshot.phone() == null || snapshot.phone().isBlank()) {
                continue;
            }
            Order patch = new Order();
            patch.setId(order.getId());
            patch.setAddressSnapshot(new AddressSnapshot(snapshot.contactName(),
                    MaskUtil.phoneShort(snapshot.phone()), snapshot.detail(),
                    snapshot.regionName(), snapshot.tag()));
            orderMapper.updateById(patch);
            masked++;
        }
        return masked;
    }

    private User requireCustomer(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("登录已失效，请重新进入小程序");
        }
        if (!UserType.CUSTOMER.equals(user.getUserType())) {
            throw BizException.param("商家账号不能在这里注销");
        }
        return user;
    }

    private long countOrders(Long userId) {
        Long count = orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId));
        return count == null ? 0L : count;
    }

    private long countAddresses(Long userId) {
        Long count = addressMapper.selectCount(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId));
        return count == null ? 0L : count;
    }

    /**
     * 注销前告知。
     *
     * @param orderCount   名下订单数；大于 0 时走匿名化，UI 要明确告诉用户「订单会留着」
     * @param addressCount 会一起删掉的收货地址数
     * @param mode         {@link #MODE_ANONYMIZE} 或 {@link #MODE_DELETE}
     * @param note         给用户读的一句话说明
     */
    public record DeletionPreview(long orderCount, long addressCount, String mode,
                                  String nickName, String note) {
    }

    /**
     * @param mode             实际走的分支
     * @param ordersKept       保留下来的订单数（无订单时为 0）
     * @param addressesRemoved 一并删除的地址条数
     */
    public record DeletionResult(String mode, long ordersKept, int addressesRemoved) {
    }
}

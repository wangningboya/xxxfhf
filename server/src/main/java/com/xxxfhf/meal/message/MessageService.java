package com.xxxfhf.meal.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.PageResult;
import com.xxxfhf.meal.notify.NotifyType;
import com.xxxfhf.meal.order.Order;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import com.xxxfhf.meal.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 站内消息：写入、查询、已读、清理。
 *
 * <p><b>写入失败绝不抛异常</b>：站内消息是订阅消息的补充，不能因为它写不进去
 * 就把「接单」本身搞失败。所有写入都包在 try-catch 里，只留 error 日志。
 *
 * <p><b>两种收件范围</b>：
 * <ul>
 *   <li>用户侧按 {@code user_id} 逐人，一人一份、各自算未读；</li>
 *   <li>商家侧是<b>全店共享</b>的一个收件箱（{@code user_id} 为 null），任何商家账号
 *       都能看、都能标已读，标的时候把「谁点的」记进 {@code read_by_id/read_by_name}。</li>
 * </ul>
 * 共享而不是逐账号复制：商家要的是「这件事店里有人处理过了」。逐账号存会让
 * A 读过之后 B 的红点还亮着，同一件事被反复提醒。
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** 与 user_message.title 的列宽一致 */
    public static final int MAX_TITLE = 64;
    /** 与 user_message.content 的列宽一致 */
    public static final int MAX_CONTENT = 255;
    /** 与 user_message.read_by_name 的列宽一致 */
    public static final int MAX_READER = 64;
    /** 首页滚动条最多取几条未读 */
    public static final int SUMMARY_LIMIT = 3;

    private final UserMessageMapper messageMapper;
    private final UserMapper userMapper;

    public MessageService(UserMessageMapper messageMapper, UserMapper userMapper) {
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
    }

    /* ==================== 写入 ==================== */

    /**
     * 写站内消息，**独立事务**。
     *
     * <p>调用点在 {@code NotifyService.doFire()} 的最前面，而 doFire 跑在
     * {@code afterCommit} 里 —— 此时业务事务已提交、连接仍绑在线程上（autoCommit = false），
     * 直接 insert 不会自动落盘，归还连接池时被回滚，消息会**静默消失**。
     * {@code REQUIRES_NEW} 挂起外层同步状态、拿新连接独立提交。
     *
     * <p>商家侧只写**一条**共享消息，不按账号展开，也不筛 openid ——
     * 站内消息不经过微信，没绑定微信的商家账号同样该看到新订单。
     *
     * @param title   标题，超长自动截断
     * @param content 一行摘要，超长自动截断
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Order order, NotifyType type, String title, String content) {
        if (order == null || order.getId() == null) {
            return;
        }
        try {
            if (type.recipient() == NotifyType.Recipient.MERCHANT) {
                insert(null, UserMessage.SIDE_MERCHANT, order.getId(), type.code(), title, content);
                return;
            }
            if (order.getUserId() == null) {
                return;
            }
            insert(order.getUserId(), UserMessage.SIDE_CUSTOMER, order.getId(), type.code(), title, content);
        } catch (RuntimeException e) {
            // 与 order_status_log 同理：留痕是副产品，失败不能影响推送与主流程
            log.error("写站内消息失败 orderNo={} type={}", order.getOrderNo(), type.code(), e);
        }
    }

    /**
     * 写一条**不挂订单**的站内消息给某个用户（v3.50 / 需求 4.12「非订单类」）。
     *
     * <p>审核通过与驳回、地址驳回与收回都不属于任何一笔订单，{@code order_id} 留空 ——
     * 表结构本来就允许（列注释写了「为将来非订单类消息留空」）。
     *
     * <p>同样独立事务、同样吞异常：审核动作不能因为写不进消息而回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordToUser(Long userId, NoticeType type, String title, String content) {
        if (userId == null) {
            return;
        }
        try {
            insert(userId, UserMessage.SIDE_CUSTOMER, null, type.code(), title, content);
        } catch (RuntimeException e) {
            log.error("写站内消息失败 userId={} type={}", userId, type.code(), e);
        }
    }

    /** 给全店共享收件箱写一条不挂订单的消息（如「昨日 N 笔订单超时关闭」的汇总）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordToMerchant(NotifyType type, String title, String content) {
        try {
            insert(null, UserMessage.SIDE_MERCHANT, null, type.code(), title, content);
        } catch (RuntimeException e) {
            log.error("写商家站内消息失败 type={}", type.code(), e);
        }
    }

    private void insert(Long userId, String side, Long orderId, String msgType,
                        String title, String content) {
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setUserType(side);
        message.setOrderId(orderId);
        message.setMsgType(msgType);
        message.setTitle(truncate(title, MAX_TITLE));
        message.setContent(truncate(content, MAX_CONTENT));
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    /* ==================== 查询 ==================== */

    /**
     * 首页用：未读总数 + 最多 3 条未读（新的在前）。
     *
     * <p>未读数用 {@code selectCount} 单独查，不拿列表长度充当：
     * 列表被 LIMIT 3 截断后，长度永远是 3，铃铛角标就废了。
     */
    public Summary summary(Long userId, String side) {
        Long unread = messageMapper.selectCount(scope(userId, side).isNull(UserMessage::getReadAt));

        List<UserMessage> latest = messageMapper.selectList(scope(userId, side)
                .isNull(UserMessage::getReadAt)
                .orderByDesc(UserMessage::getCreatedAt)
                .orderByDesc(UserMessage::getId)
                // 常量拼接，不来自前端，无注入面
                .last("LIMIT " + SUMMARY_LIMIT));

        List<Row> items = new ArrayList<>(latest.size());
        for (UserMessage message : latest) {
            items.add(toRow(message));
        }
        return new Summary(unread == null ? 0 : unread, items);
    }

    /** 消息中心列表，按时间倒序分页。 */
    public PageResult<Row> page(Long userId, String side, int page, int pageSize) {
        IPage<UserMessage> result = messageMapper.selectPage(
                new Page<>(Math.max(page, 1), clampSize(pageSize)),
                scope(userId, side)
                        .orderByDesc(UserMessage::getCreatedAt)
                        .orderByDesc(UserMessage::getId));

        List<Row> rows = new ArrayList<>(result.getRecords().size());
        for (UserMessage message : result.getRecords()) {
            rows.add(toRow(message));
        }
        return new PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /* ==================== 已读 ==================== */

    /**
     * 单条标已读。
     *
     * <p>归属校验分两种：用户侧只许本人（消息 ID 自增，不校验的话任何人换个 ID
     * 就能改别人的未读状态）；商家侧是共享收件箱，任何<b>商家账号</b>都能标，
     * 但必须是个商家账号 —— 普通用户不许动商家那堆消息。
     *
     * <p>共享消息把点的人记下来：商家要知道「这单是谁先看到的」。
     */
    public void markRead(Long userId, String userType, Long messageId) {
        UserMessage message = messageId == null ? null : messageMapper.selectById(messageId);
        if (message == null) {
            throw BizException.notFound("消息不存在");
        }
        if (!readableBy(message, userId, userType)) {
            throw BizException.forbidden("不能操作别人的消息");
        }
        if (message.getReadAt() != null) {
            return;
        }
        UserMessage update = new UserMessage();
        update.setId(messageId);
        update.setReadAt(LocalDateTime.now());
        if (UserMessage.SIDE_MERCHANT.equals(message.getUserType())) {
            update.setReadById(userId);
            update.setReadByName(readerName(userId));
        }
        messageMapper.updateById(update);
    }

    /**
     * 扫把：把当前这一侧的全部未读标已读。
     *
     * <p>商家侧清的是全店共享那一份，不是「我这个账号的」，与共享收件箱口径一致。
     * 只动 {@code side} 指定的那一侧：箱子里同时有「我买的」和「卖给我的」两批消息，
     * 在商家工作台点扫把不该顺手把用户侧的红点也清掉。
     *
     * @return 本次标已读的条数
     */
    public int markAllRead(Long userId, String userType, String side) {
        String target = sideOf(side);
        UserMessage update = new UserMessage();
        update.setReadAt(LocalDateTime.now());

        LambdaUpdateWrapper<UserMessage> wrapper = Wrappers.<UserMessage>lambdaUpdate()
                .eq(UserMessage::getUserType, target)
                .isNull(UserMessage::getReadAt);
        if (UserMessage.SIDE_MERCHANT.equals(target)) {
            // 共享箱：所有商家账号看到同一份，这里只记操作人
            update.setReadById(userId);
            update.setReadByName(readerName(userId));
        } else {
            // 个人箱：只清自己的
            wrapper.eq(UserMessage::getUserId, userId);
        }
        return messageMapper.update(update, wrapper);
    }

    /* ==================== 清理 ==================== */

    /**
     * 删除 {@code days} 天前的消息，**含未读**，分批提交避免长事务锁表。
     *
     * <p>未读一并删：90 天没被点开的消息，留着也不会再点开；保留它只会让
     * 「未读数」这个指标失去意义。副作用是消息中心可能变空，属于预期行为。
     *
     * @return 实际删除条数
     */
    public int cleanup(int days, int batchSize) {
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        int total = 0;
        while (true) {
            int deleted = messageMapper.deleteBefore(before, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                return total;
            }
        }
    }

    /* ==================== 内部 ==================== */

    /**
     * 查询范围。
     *
     * <p>两边条件不同：用户侧必须带 {@code user_id}（只看自己的），
     * 商家侧只认 {@code user_type}（全店一份）。硬套同一个条件会让商家侧查到空结果 ——
     * 共享消息的 {@code user_id} 是 null，永远等不上某个具体账号。
     */
    private static LambdaQueryWrapper<UserMessage> scope(Long userId, String side) {
        LambdaQueryWrapper<UserMessage> wrapper = Wrappers.lambdaQuery();
        if (UserMessage.SIDE_MERCHANT.equals(side)) {
            wrapper.eq(UserMessage::getUserType, UserMessage.SIDE_MERCHANT);
        } else {
            wrapper.eq(UserMessage::getUserId, userId)
                    .eq(UserMessage::getUserType, UserMessage.SIDE_CUSTOMER);
        }
        return wrapper;
    }

    private static boolean readableBy(UserMessage message, Long userId, String userType) {
        if (UserMessage.SIDE_MERCHANT.equals(message.getUserType())) {
            return UserType.MERCHANT.equals(userType);
        }
        return message.getUserId() != null && message.getUserId().equals(userId);
    }

    /**
     * 读消息的那个人的显示名：昵称优先，没填昵称用登录账号。
     *
     * <p>落库快照而不是每次 join 用户表：账号被改名或注销后，历史消息上
     * 仍要能看出「当时是谁处理的」。
     */
    private String readerName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        String name = user.getNickName();
        if (name == null || name.isBlank()) {
            name = user.getUsername();
        }
        return name == null ? null : truncate(name, MAX_READER);
    }

    /** side 缺省按用户侧处理：前端不传就是用户端页面。 */
    private static String sideOf(String side) {
        return UserMessage.SIDE_MERCHANT.equals(side)
                ? UserMessage.SIDE_MERCHANT : UserMessage.SIDE_CUSTOMER;
    }

    private static int clampSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private static Row toRow(UserMessage message) {
        return new Row(message.getId(), message.getMsgType(), message.getTitle(),
                message.getContent(), message.getOrderId(), message.getUserType(),
                message.getReadAt() == null, message.getReadByName(), message.getReadAt(),
                message.getCreatedAt());
    }

    private static String truncate(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * 首页摘要。
     *
     * @param unreadCount 这一侧的未读总数，不是 {@code items} 的长度
     * @param items       最多 3 条未读，新的在前
     */
    public record Summary(long unreadCount, List<Row> items) {
    }

    /**
     * 一行消息。
     *
     * @param unread     未读为 true；已读为 false
     * @param readByName 商家侧共享消息上「谁点的已读」，用户侧恒为 null
     * @param readAt     已读时间；未读为 null
     * @param userType   customer / merchant，前端据此决定点击后跳哪个订单详情
     */
    public record Row(Long id, String msgType, String title, String content,
                      Long orderId, String userType, boolean unread,
                      String readByName, LocalDateTime readAt, LocalDateTime createdAt) {
    }
}

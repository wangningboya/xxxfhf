package com.xxxfhf.meal.message.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 站内消息。对应表 {@code user_message}（见 站内通知需求.md）。
 *
 * <p>与订阅消息的关系：订阅消息是「推送到微信」，本表是「留在小程序里」。
 * 推送会因为未授权、额度用完、用户拒绝而失败，且失败后手机端毫无痕迹；
 * 本表在推送之前就写好了，所以只要用户打开小程序就一定看得到。
 *
 * <p>{@code userType} 存的是**这条消息属于哪一侧**（customer / merchant），
 * 不是接收人账号的类型：商家自己下单时，他收到的「商家已接单」属于用户侧。
 *
 * <p>收件范围两种：用户侧按 {@code userId} 逐人，一人一份；商家侧是**全店共享**的
 * 一个收件箱，{@code userId} 为 null，任何商家账号都能看、都能标已读 ——
 * 共享而不是逐账号复制，否则 A 读过之后 B 的红点还亮着，同一件事被反复提醒。
 * 谁点的已读记在 {@code readById} / {@code readByName}。
 *
 * <p>追加型：只 insert 与更新 {@code read_at}，业务上不删；清理由 90 天任务按
 * {@code createdAt} 批量删除。
 */
@TableName("user_message")
public class UserMessage {

    /** 消息属于用户侧 */
    public static final String SIDE_CUSTOMER = "customer";

    /** 消息属于商家侧 */
    public static final String SIDE_MERCHANT = "merchant";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 user_id；商家侧共享收件箱为 null */
    private Long userId;

    /** customer 用户侧 / merchant 商家侧 */
    private String userType;

    /** 关联订单；将来非订单类消息（公告、系统通知）为空 */
    private Long orderId;

    /** 与 {@code NotifyType.code} 一致，如 accepted / newOrder */
    private String msgType;

    private String title;

    private String content;

    /** 已读时间；null 即未读 */
    private LocalDateTime readAt;

    /** 点已读的人；null 即未读 */
    private Long readById;

    /** 点已读的人的显示名，落库快照 —— 事后改昵称不回溯，要的是「当时是谁处理的」 */
    private String readByName;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public Long getReadById() {
        return readById;
    }

    public void setReadById(Long readById) {
        this.readById = readById;
    }

    public String getReadByName() {
        return readByName;
    }

    public void setReadByName(String readByName) {
        this.readByName = readByName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

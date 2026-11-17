package com.xxxfhf.meal.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单。表名 `order` 是 MySQL 关键字，必须带反引号。
 *
 * 四组 JSON 快照（region / address / items / cancelRequest）+ 金额四件套，
 * 共同支撑一条铁律：**订单不依赖实时表**。改价、改配菜、改区域名、删地址，
 * 历史订单与备菜统计都不受影响。
 */
@TableName(value = "`order`", autoResultMap = true)
public class Order {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 订单号，yyyyMMdd-000001，对人报单号用这个 */
    private String orderNo;

    /**
     * 下单幂等号，前端每次提交生成一个，靠唯一索引拦住双击与重试。
     * 手工补单、代客下单可以留空（MySQL 唯一索引允许多个 NULL）。
     */
    private String requestId;

    private String userId;

    private LocalDate serveDate;

    /** pickup 自取 / delivery 配送 */
    private String deliveryType;

    /** 配送单的区域；**自取单为空**（自取单没有地址，也就没有区域） */
    private String regionId;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private RegionSnapshot regionSnapshot;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private AddressSnapshot addressSnapshot;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<OrderItem> items;

    private Integer goodsAmount;
    private Integer specAmount;
    private Integer deliveryFee;
    private Integer totalAmount;

    /** pending_accept / accepted / making / ready / completed / cancelled / rejected */
    private String status;

    /** 取消申请中。为 true 时商家端禁用接单，必须先批或驳 */
    private Boolean cancelRequested;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private CancelRequest cancelRequest;

    /**
     * 取消申请提交时间。
     *
     * 单独存一列而不是从 cancel_request JSON 里取：超时自动同意任务要按时间范围扫描，
     * 只有独立列才能走索引（JSON 里取字段是逐行计算，几百单就会拖慢）。
     */
    private LocalDateTime cancelRequestedAt;

    /** unpaid / claimed / paid / refunding / refunded */
    private String payStatus;

    private String payMethod;

    /** 4 位取餐码，当日唯一。配送单同样生成，送达时核对 */
    private String pickupCode;

    /** 自取时段，仅自取单有值 */
    private String pickupSlot;

    /** 份数是否已被「超时未付款」释放；为 true 时商家端置顶标红 */
    private Boolean stockReleased;
    private LocalDateTime stockReleasedAt;

    /** 商家拒单原因。必填，用户端订单详情直接展示 */
    private String rejectReason;

    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime paidAt;
    private LocalDateTime refundedAt;

    /** 标记退款的操作人，防重复退 */
    private String refundOperator;

    /* ---------------- getters / setters ---------------- */

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public LocalDate getServeDate() {
        return serveDate;
    }

    public void setServeDate(LocalDate serveDate) {
        this.serveDate = serveDate;
    }

    public String getDeliveryType() {
        return deliveryType;
    }

    public void setDeliveryType(String deliveryType) {
        this.deliveryType = deliveryType;
    }

    public String getRegionId() {
        return regionId;
    }

    public void setRegionId(String regionId) {
        this.regionId = regionId;
    }

    public RegionSnapshot getRegionSnapshot() {
        return regionSnapshot;
    }

    public void setRegionSnapshot(RegionSnapshot regionSnapshot) {
        this.regionSnapshot = regionSnapshot;
    }

    public AddressSnapshot getAddressSnapshot() {
        return addressSnapshot;
    }

    public void setAddressSnapshot(AddressSnapshot addressSnapshot) {
        this.addressSnapshot = addressSnapshot;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public Integer getGoodsAmount() {
        return goodsAmount;
    }

    public void setGoodsAmount(Integer goodsAmount) {
        this.goodsAmount = goodsAmount;
    }

    public Integer getSpecAmount() {
        return specAmount;
    }

    public void setSpecAmount(Integer specAmount) {
        this.specAmount = specAmount;
    }

    public Integer getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(Integer deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public Integer getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Integer totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(Boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public CancelRequest getCancelRequest() {
        return cancelRequest;
    }

    public void setCancelRequest(CancelRequest cancelRequest) {
        this.cancelRequest = cancelRequest;
    }

    public LocalDateTime getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public void setCancelRequestedAt(LocalDateTime cancelRequestedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public String getPayMethod() {
        return payMethod;
    }

    public void setPayMethod(String payMethod) {
        this.payMethod = payMethod;
    }

    public String getPickupCode() {
        return pickupCode;
    }

    public void setPickupCode(String pickupCode) {
        this.pickupCode = pickupCode;
    }

    public String getPickupSlot() {
        return pickupSlot;
    }

    public void setPickupSlot(String pickupSlot) {
        this.pickupSlot = pickupSlot;
    }

    public Boolean getStockReleased() {
        return stockReleased;
    }

    public void setStockReleased(Boolean stockReleased) {
        this.stockReleased = stockReleased;
    }

    public LocalDateTime getStockReleasedAt() {
        return stockReleasedAt;
    }

    public void setStockReleasedAt(LocalDateTime stockReleasedAt) {
        this.stockReleasedAt = stockReleasedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public void setRefundedAt(LocalDateTime refundedAt) {
        this.refundedAt = refundedAt;
    }

    public String getRefundOperator() {
        return refundOperator;
    }

    public void setRefundOperator(String refundOperator) {
        this.refundOperator = refundOperator;
    }
}

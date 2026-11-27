package com.xxxfhf.meal.print.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 标签打印记录。对应表 {@code order_print_page}（见 打印需求.md 第六章）。
 *
 * <p><b>一行 = 一张纸</b>。端上是一张一张推给打印机的（{@code count} 恒为 1），
 * 所以这张表的粒度必须与出纸粒度一致：中间卡纸时才知道缺的是哪一张，
 * 补打也才能只补那一张。按「一次作业一行」存的话，一次打 5 张只留一条失败记录，
 * 答不出「这单的哪个套餐没出纸」。
 *
 * <p>{@code pageKey} 由端上生成（{@code o1024-i1-c2} = 订单 + 套餐下标 + 份序号），
 * 与 {@code orderId} 组成唯一键：网络重试导致的重复上报要变成幂等更新，不能多算一张纸。
 *
 * <p>追加型为主，但因为要处理重复上报，写入走「按唯一键查，存在则更新」。
 */
@TableName("order_print_page")
public class OrderPrintPage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 端上生成的作业号，同一批共用一个 */
    private String batchNo;

    private Long orderId;

    /** 页标识，如 o1024-i1-c2；与 orderId 组成唯一键 */
    private String pageKey;

    /** 供餐日冗余，便于按天统计耗材 */
    private LocalDate serveDate;

    /** delivery 配送单 / pickup 自取单 */
    private String template;

    /** success / fail */
    private String result;

    /** 失败时的 SDK 错误码，成功为 null */
    private Integer errCode;

    private Long operatorId;

    /** 操作人名称快照：改昵称、删账号都不影响历史记录 */
    private String operatorName;

    /** 打印机广播名，如 B3S-E623012991 */
    private String deviceName;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getPageKey() {
        return pageKey;
    }

    public void setPageKey(String pageKey) {
        this.pageKey = pageKey;
    }

    public LocalDate getServeDate() {
        return serveDate;
    }

    public void setServeDate(LocalDate serveDate) {
        this.serveDate = serveDate;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Integer getErrCode() {
        return errCode;
    }

    public void setErrCode(Integer errCode) {
        this.errCode = errCode;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

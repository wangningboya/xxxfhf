package com.xxxfhf.meal.menu;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.time.LocalDate;

/**
 * 每日菜单的一行：某供餐日供应的某个盒饭及其限量。
 *
 * **价格与组成在这里是快照**，不是实时读取盒饭库 —— 这是「已发布菜单改价不影响已下单」
 * 能够成立的唯一原因。下单时再从这一行把快照抄进订单。
 */
@TableName(value = "daily_menu", autoResultMap = true)
public class DailyMenu {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 供餐日期，只能是未来日期（今天及过去不可选） */
    private LocalDate serveDate;

    private String comboId;

    /** 盒饭名称 / 封面 / 组成菜品 / 规格组的完整快照 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private ComboSnapshot comboSnapshot;

    /** 盒饭价格快照，单位分 */
    private Integer priceSnapshot;

    /** 限量总份数；null 表示不限 */
    private Integer limitQty;

    /** 已占用份数（下单即占；拒单 / 取消 / 超时释放时减回） */
    private Integer soldQty;

    /** draft / published */
    private String status;

    /* ---------------- getters / setters ---------------- */

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDate getServeDate() {
        return serveDate;
    }

    public void setServeDate(LocalDate serveDate) {
        this.serveDate = serveDate;
    }

    public String getComboId() {
        return comboId;
    }

    public void setComboId(String comboId) {
        this.comboId = comboId;
    }

    public ComboSnapshot getComboSnapshot() {
        return comboSnapshot;
    }

    public void setComboSnapshot(ComboSnapshot comboSnapshot) {
        this.comboSnapshot = comboSnapshot;
    }

    public Integer getPriceSnapshot() {
        return priceSnapshot;
    }

    public void setPriceSnapshot(Integer priceSnapshot) {
        this.priceSnapshot = priceSnapshot;
    }

    public Integer getLimitQty() {
        return limitQty;
    }

    public void setLimitQty(Integer limitQty) {
        this.limitQty = limitQty;
    }

    public Integer getSoldQty() {
        return soldQty;
    }

    public void setSoldQty(Integer soldQty) {
        this.soldQty = soldQty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

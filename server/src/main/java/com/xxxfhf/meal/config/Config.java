package com.xxxfhf.meal.config;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 系统配置。表 config 用 config_key 作主键（不是 id），所以主键类型必须显式声明 INPUT。
 */
@TableName("config")
public class Config {

    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    private String configValue;

    private String remark;

    /**
     * 由数据库维护（DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP），应用侧只读。
     * 显式禁止参与 insert/update：否则插入 null 会覆盖掉 DB 默认值。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

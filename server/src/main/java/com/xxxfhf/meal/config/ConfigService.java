package com.xxxfhf.meal.config;

import com.xxxfhf.meal.audit.domain.AuditActions;
import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 系统配置读写。
 *
 * 不做进程内缓存：config 表只有十来行、全部是主键查询，300 单/天的量完全不成负担；
 * 缓存反而会带来「商家在后台改了截单时间，服务端还按旧值算」这类极难排查的问题。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigMapper configMapper;
    private final ObjectMapper objectMapper;
    /** 配置变更留痕（需求 4.19 / F-J13）。配置改动没有界面可回看，只有这里能查「谁把截单时间改了」 */
    private final AuditService auditService;

    public ConfigService(ConfigMapper configMapper, ObjectMapper objectMapper,
                         AuditService auditService) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /* ---------------- 读 ---------------- */

    /** 取原始字符串，不存在返回 null。 */
    public String get(String key) {
        Config config = configMapper.selectById(key);
        return config == null ? null : config.getConfigValue();
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            log.warn("配置项 {} 不是整数，值={}，按默认值 {} 处理", key, value, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    /**
     * 取正整数配置，**小于 1 一律按 1 处理**。
     *
     * 份数上限这类值不能用 {@link #getInt} 直接取：配成 0 时 {@code qty > 0} 会让
     * 所有订单都下不了，属于全站停单级别的故障。保存接口已经把范围卡在 ≥1，
     * 但库里可能被手工改脏，所以读取侧再兜一层 —— 宁可只允许 1 份，也不能一单都下不了。
     */
    public int getPositiveInt(String key, int defaultValue) {
        int value = getInt(key, defaultValue);
        if (value < 1) {
            log.warn("配置项 {} 的值 {} 小于 1，按 1 处理", key, value);
            return 1;
        }
        return value;
    }

    /**
     * 取 JSON 数组配置。值缺失、为空数组或解析失败一律返回空列表——
     * 调用方（如群白名单校验）拿到空列表意味着「谁都不放行」，这是安全的一侧。
     */
    public List<String> getStringList(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            String[] array = objectMapper.readValue(value, String[].class);
            List<String> result = new ArrayList<>(array.length);
            for (String item : array) {
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
            return result;
        } catch (Exception ex) {
            log.error("配置项 {} 不是合法的 JSON 字符串数组，值={}，按空列表处理", key, value, ex);
            return List.of();
        }
    }

    /** 全部配置，供后台配置页一次读回。按 key 排序，保证每次返回顺序一致。 */
    public Map<String, String> getAll() {
        List<Config> list = configMapper.selectList(null);
        Map<String, String> map = new LinkedHashMap<>();
        list.sort((a, b) -> a.getConfigKey().compareTo(b.getConfigKey()));
        for (Config config : list) {
            map.put(config.getConfigKey(), config.getConfigValue());
        }
        return map;
    }

    /* ---------------- 写 ---------------- */

    /** 写单个配置项。key 不存在则插入。 */
    @Transactional
    public void set(String key, String value) {
        if (key == null || key.isBlank()) {
            throw BizException.param("配置键不能为空");
        }
        String newValue = value == null ? "" : value;
        String oldValue = get(key);
        write(key, newValue);
        if (!Objects.equals(oldValue == null ? "" : oldValue, newValue)) {
            auditService.record(AuditActions.TABLE_CONFIG, 0L, AuditActions.CONFIG_UPDATE,
                    "修改配置：" + keyLabel(key) + " " + brief(oldValue) + " → " + brief(newValue));
        }
    }

    /** 只落库不留痕，给 {@link #setAll} 批量写用（批量只在最后记一条汇总） */
    private void write(String key, String value) {
        Config config = new Config();
        config.setConfigKey(key);
        config.setConfigValue(value);
        // 存在则更新：updateById 会按主键 config_key 定位
        if (configMapper.selectById(key) == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        log.info("配置已更新 key={}", key);
    }

    /**
     * 批量写。只允许写已登记的键，防止后台随手拼个新键把配置表搞乱。
     */
    @Transactional
    public void setAll(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> unknown = new ArrayList<>();
        for (String key : values.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw BizException.param("不认识的配置项：" + String.join(", ", unknown));
        }

        // 后台配置页一次提交全部键，逐键写一条会刷出十几行流水；这里先比对新旧，
        // 只把**真的变了**的键拼成一条，一个键都没变则不留痕（避免「点了保存」就出一条空记录）。
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue() == null ? "" : entry.getValue();
            String oldValue = get(key);
            write(key, newValue);
            if (!Objects.equals(oldValue == null ? "" : oldValue, newValue)) {
                changes.add(keyLabel(key) + " " + brief(oldValue) + " → " + brief(newValue));
            }
        }
        if (!changes.isEmpty()) {
            auditService.record(AuditActions.TABLE_CONFIG, 0L, AuditActions.CONFIG_UPDATE,
                    "修改配置：" + String.join("；", changes));
        }
    }

    /** 配置键的中文名，用于操作记录摘要；未登记的原样返回，避免出现空白 */
    public static String keyLabel(String key) {
        return KEY_LABELS.getOrDefault(key, key);
    }

    /**
     * 摘要里的值展示：空值写「空」，过长（群白名单这种 JSON 数组）截断 ——
     * {@code audit_log.summary} 只有 500 字，一条白名单就能把它撑爆。
     */
    private static String brief(String value) {
        if (value == null || value.isBlank()) {
            return "空";
        }
        String text = value.trim();
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /**
     * 写 JSON 数组配置（停业日、群白名单这类）。
     *
     * 收口在 ConfigService 而不是让调用方各自序列化：JSON 的写法只要有一处不一致
     * （比如存成 ["a","b"] 与存成 a,b），读的那一侧就会安静地拿到空列表。
     */
    @Transactional
    public void setStringList(String key, List<String> values) {
        try {
            String json = objectMapper.writeValueAsString(values == null ? List.of() : values);
            set(key, json);
        } catch (Exception ex) {
            throw BizException.param("配置项 " + key + " 写入失败：" + ex.getMessage());
        }
    }

    /** 配置键的中文名，顺序与 {@link #KNOWN_KEYS} 一致 */
    private static final Map<String, String> KEY_LABELS = Map.ofEntries(
            Map.entry(ConfigKey.CUTOFF_TIME, "截单时间"),
            Map.entry(ConfigKey.SERVE_TIME_TEXT, "供餐时间"),
            Map.entry(ConfigKey.PICKUP_SLOTS, "取餐时段"),
            Map.entry(ConfigKey.DELIVERY_FEE, "配送费"),
            Map.entry(ConfigKey.SHOP_NAME, "店铺名称"),
            Map.entry(ConfigKey.SHOP_NOTICE, "店铺公告"),
            Map.entry(ConfigKey.SHOP_ADDRESS, "店铺地址"),
            Map.entry(ConfigKey.SHOP_PHONE, "商家联系电话"),
            Map.entry(ConfigKey.PREPAY_RELEASE_MINUTES, "未申报释放分钟数"),
            Map.entry(ConfigKey.CANCEL_REQUEST_TIMEOUT_HOURS, "取消申请超时小时数"),
            Map.entry(ConfigKey.CLOSED_DATES, "停业日"),
            Map.entry(ConfigKey.GROUP_OPEN_IDS, "群白名单"),
            Map.entry(ConfigKey.SUBSCRIBE_TEMPLATES, "订阅消息模板"),
            Map.entry(ConfigKey.ORDER_MAX_ITEM_KINDS, "一单最多几种盒饭"),
            Map.entry(ConfigKey.ORDER_MAX_QTY_PER_ITEM, "单个盒饭最多份数"),
            Map.entry(ConfigKey.ORDER_MAX_QTY_TOTAL, "一单总份数上限"),
            Map.entry(ConfigKey.COMBO_DISH_MAX_QTY, "盒饭内单个菜品份数"),
            Map.entry(ConfigKey.ORDER_BATCH_LIMIT, "批量接单/拒单上限"),
            Map.entry(ConfigKey.INVITE_MAX_EXPIRE_HOURS, "邀请码有效期上限")
    );

    /** 已知配置项的完整清单，与建表初始数据保持一致。 */
    public static final List<String> KNOWN_KEYS = List.of(
            ConfigKey.CUTOFF_TIME,
            ConfigKey.SERVE_TIME_TEXT,
            ConfigKey.PICKUP_SLOTS,
            ConfigKey.DELIVERY_FEE,
            ConfigKey.SHOP_NAME,
            ConfigKey.SHOP_NOTICE,
            ConfigKey.SHOP_ADDRESS,
            ConfigKey.SHOP_PHONE,
            ConfigKey.PREPAY_RELEASE_MINUTES,
            ConfigKey.CANCEL_REQUEST_TIMEOUT_HOURS,
            ConfigKey.CLOSED_DATES,
            ConfigKey.GROUP_OPEN_IDS,
            ConfigKey.SUBSCRIBE_TEMPLATES,
            ConfigKey.ORDER_MAX_ITEM_KINDS,
            ConfigKey.ORDER_MAX_QTY_PER_ITEM,
            ConfigKey.ORDER_MAX_QTY_TOTAL,
            ConfigKey.COMBO_DISH_MAX_QTY,
            ConfigKey.ORDER_BATCH_LIMIT,
            ConfigKey.INVITE_MAX_EXPIRE_HOURS
    );
}

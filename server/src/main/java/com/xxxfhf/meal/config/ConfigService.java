package com.xxxfhf.meal.config;

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

    public ConfigService(ConfigMapper configMapper, ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
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
        Config config = new Config();
        config.setConfigKey(key);
        config.setConfigValue(value == null ? "" : value);
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
        values.forEach(this::set);
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

    /** 已知配置项的完整清单，与建表初始数据保持一致。 */
    public static final List<String> KNOWN_KEYS = List.of(
            ConfigKey.CUTOFF_TIME,
            ConfigKey.SERVE_TIME_TEXT,
            ConfigKey.PICKUP_SLOTS,
            ConfigKey.DELIVERY_FEE,
            ConfigKey.SHOP_NAME,
            ConfigKey.SHOP_NOTICE,
            ConfigKey.PHONE_AUTH_ENABLED,
            ConfigKey.PREPAY_RELEASE_MINUTES,
            ConfigKey.CLOSED_DATES,
            ConfigKey.GROUP_OPEN_IDS,
            ConfigKey.SUBSCRIBE_TEMPLATES
    );
}

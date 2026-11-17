package com.xxxfhf.meal.config;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统配置。
 *
 * 分两个出口：
 * - GET /config       小程序用，只返回用户该看到的项
 * - GET /admin/config 后台配置页用，返回全部（含群白名单这类内部参数）
 */
@RestController
public class ConfigController {

    /** 小程序可见的配置项。群白名单、释放分钟数属于内部参数，不下发。 */
    private static final String[] PUBLIC_KEYS = {
            ConfigKey.SHOP_NAME,
            ConfigKey.SHOP_NOTICE,
            ConfigKey.SERVE_TIME_TEXT,
            ConfigKey.CUTOFF_TIME,
            ConfigKey.PICKUP_SLOTS,
            ConfigKey.DELIVERY_FEE,
            ConfigKey.CLOSED_DATES
    };

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /** 小程序读取店铺信息与截单时间等。需登录（不登录拿不到菜单也用不上）。 */
    @GetMapping("/config")
    public ApiResponse<Map<String, String>> publicConfig() {
        Map<String, String> all = configService.getAll();
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            String value = all.get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return ApiResponse.ok(result);
    }

    /** 后台配置页读取全部配置项。 */
    @GetMapping("/admin/config")
    @RequirePerm(Perms.CONFIG_MANAGE)
    public ApiResponse<Map<String, String>> adminConfig() {
        return ApiResponse.ok(configService.getAll());
    }

    /**
     * 后台保存配置。只接受已知键名，未知键直接拒绝。
     *
     * 群白名单 groupOpenIds 必须是 JSON 字符串数组，格式不对一律拒绝——
     * 写错会让所有人进不来（解析失败按空列表处理 = 全拒）。
     */
    @PostMapping("/admin/config")
    @RequirePerm(Perms.CONFIG_MANAGE)
    public ApiResponse<Void> save(@RequestBody Map<String, String> values) {
        validateGroupOpenIds(values.get(ConfigKey.GROUP_OPEN_IDS));
        configService.setAll(values);
        return ApiResponse.ok();
    }

    private void validateGroupOpenIds(String raw) {
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw BizException.param("groupOpenIds 必须是 JSON 字符串数组，例如 [\"Gxxxxx\"]");
        }
    }
}

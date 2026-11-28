package com.xxxfhf.meal.config;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.PhoneValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            ConfigKey.SHOP_ADDRESS,
            ConfigKey.SHOP_PHONE,
            ConfigKey.SERVE_TIME_TEXT,
            ConfigKey.CUTOFF_TIME,
            ConfigKey.PICKUP_SLOTS,
            ConfigKey.DELIVERY_FEE,
            ConfigKey.CLOSED_DATES,
            // 下单页要用它算加号上限：不下发的话前端只能继续写死一个假上限，
            // 后台改了配置前端不跟，用户能加到超限份数、提交时才被打回
            ConfigKey.ORDER_MAX_ITEM_KINDS,
            ConfigKey.ORDER_MAX_QTY_PER_ITEM,
            ConfigKey.ORDER_MAX_QTY_TOTAL
    };

    /**
     * 业务限制项的保存上限。
     *
     * <p>当前**等于默认值**，即商家只能往下调、不能往上调：这六个数字是按实际经营规模定的硬顶。
     * 代价是「某天菜单出现第 5 种盒饭，后台调不上去」，届时改这里、或直接把上限改大。
     * 集中成常量而不是散在调用处，就是为了那一天只改一行。
     */
    private static final int CEIL_ITEM_KINDS = ConfigKey.ORDER_MAX_ITEM_KINDS_DEFAULT;
    private static final int CEIL_QTY_PER_ITEM = ConfigKey.ORDER_MAX_QTY_PER_ITEM_DEFAULT;
    private static final int CEIL_QTY_TOTAL = ConfigKey.ORDER_MAX_QTY_TOTAL_DEFAULT;
    private static final int CEIL_COMBO_DISH_QTY = ConfigKey.COMBO_DISH_MAX_QTY_DEFAULT;
    private static final int CEIL_BATCH = ConfigKey.ORDER_BATCH_LIMIT_DEFAULT;
    private static final int CEIL_INVITE_HOURS = ConfigKey.INVITE_MAX_EXPIRE_HOURS_DEFAULT;

    /** 截单时间：兼容「9:00」这种不补零的写法，与 ServeDateClock 的兼容口径一致 */
    private static final Pattern CUTOFF_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    /** 非负整数配置项（配送费、释放分钟、取消超时） */
    private static final Pattern NON_NEGATIVE_INT = Pattern.compile("^\\d+$");

    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public ConfigController(ConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
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
     *
     * 截单时间、自取时段、配送费、释放分钟、取消超时这五项也在这里挡住：
     * 它们写坏了不会报错，而是被读取侧静默回落（截单回 21:00、配送费回 0 = 免费、
     * 释放分钟回 30、取消超时回 0 = 关闭自动同意、自取时段回空 = 自取单下不了），
     * 商家只会看到「已保存」。后台界面本来就有 min/max 与时间选择器限着，这里补的是
     * 绕过界面直接调接口那条路。**只校验结构与范围，不校验业务合理性**（例如时段具体怎么写）。
     *
     * 六个业务限制项（份数、批量、邀请码有效期）同样卡范围，见 {@link #requireRange}。
     */
    @PostMapping("/admin/config")
    @RequirePerm(Perms.CONFIG_MANAGE)
    public ApiResponse<Void> save(@RequestBody Map<String, String> values) {
        validateGroupOpenIds(values.get(ConfigKey.GROUP_OPEN_IDS));
        validateCutoffTime(values.get(ConfigKey.CUTOFF_TIME));
        validatePickupSlots(values.get(ConfigKey.PICKUP_SLOTS));
        requireNonNegativeInt("配送费", values.get(ConfigKey.DELIVERY_FEE));
        requireNonNegativeInt("未付款释放分钟数", values.get(ConfigKey.PREPAY_RELEASE_MINUTES));
        requireNonNegativeInt("取消申请超时小时数", values.get(ConfigKey.CANCEL_REQUEST_TIMEOUT_HOURS));
        // 业务限制项：下限固定 1。0 不是「关闭限制」而是「谁都下不了单」，
        // 读取侧虽然会夹到 1，但与其让商家看到「已保存」却发现行为不对，不如当场拒掉。
        requireRange("一单最多几种盒饭", values.get(ConfigKey.ORDER_MAX_ITEM_KINDS), 1, CEIL_ITEM_KINDS);
        requireRange("单个盒饭最多份数", values.get(ConfigKey.ORDER_MAX_QTY_PER_ITEM), 1, CEIL_QTY_PER_ITEM);
        requireRange("一单总份数上限", values.get(ConfigKey.ORDER_MAX_QTY_TOTAL), 1, CEIL_QTY_TOTAL);
        requireRange("盒饭内单个菜品份数", values.get(ConfigKey.COMBO_DISH_MAX_QTY), 1, CEIL_COMBO_DISH_QTY);
        requireRange("批量接单/拒单上限", values.get(ConfigKey.ORDER_BATCH_LIMIT), 1, CEIL_BATCH);
        requireRange("邀请码有效期上限", values.get(ConfigKey.INVITE_MAX_EXPIRE_HOURS), 1, CEIL_INVITE_HOURS);
        // 门店电话留空表示不展示拨号入口；填了就必须是可拨的号，否则用户点下去是空号
        PhoneValidator.requireShopPhone(values.get(ConfigKey.SHOP_PHONE));
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

    /**
     * 截单时间必须是 HH:mm（兼容 9:00）。留空走默认 21:00，与 {@code ServeDateClock} 一致。
     *
     * 不合法时 ServeDateClock 只打一条 warn 就按 21:00 算，而首页原样展示这个脏值，
     * 会出现「页面写着 25:00 截单、实际 21:00 就截了」。
     */
    private void validateCutoffTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        Matcher matcher = CUTOFF_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw BizException.param("截单时间必须是 HH:mm，例如 21:00，当前值：" + value);
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            throw BizException.param("截单时间必须在 00:00 到 23:59 之间，当前值：" + value);
        }
    }

    /**
     * 自取时段必须是 JSON 字符串数组。留空等同「没配」，与读取侧一致。
     *
     * 只校验是不是数组，不管每行写什么：时段是纯展示文案，全项目没有解析它的地方
     * （订单只做 includes 比对），硬套 HH:mm-HH:mm 会把「午餐 11:30-12:30」判成非法。
     */
    private void validatePickupSlots(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            String[] slots = objectMapper.readValue(raw, String[].class);
            if (slots == null) {
                throw BizException.param("自取时段必须是 JSON 字符串数组，例如 [\"11:00-11:30\"]");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.param("自取时段必须是 JSON 字符串数组，例如 [\"11:00-11:30\"]");
        }
    }

    /**
     * 非负整数配置项。坏值在读取侧会被静默回落成默认值（配送费 0 = 免费配送、
     * 释放分钟 30、取消超时 0 = 关闭自动同意），所以宁可在这里拒掉。
     */
    private void requireNonNegativeInt(String label, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        if (!NON_NEGATIVE_INT.matcher(value).matches()) {
            throw BizException.param(label + "必须是非负整数，当前值：" + value);
        }
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw BizException.param(label + "超出可表示的范围，当前值：" + value);
        }
    }

    /**
     * 业务限制项：必须是 [min, max] 内的整数。
     *
     * <p>留空表示「不提交这一项」，直接放行 —— 与其它配置项的约定一致（空值不等于 0）。
     * 校验在保存时做，读取侧另有 {@code getPositiveInt} 兜底，两道都要，缺一不可：
     * 前者挡界面与接口，后者挡手工改库留下的脏值。
     */
    private void requireRange(String label, String raw, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        if (!NON_NEGATIVE_INT.matcher(value).matches()) {
            throw BizException.param(label + "必须是整数，当前值：" + value);
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw BizException.param(label + "超出可表示的范围，当前值：" + value);
        }
        if (parsed < min || parsed > max) {
            throw BizException.param(label + "必须在 " + min + " ~ " + max + " 之间，当前值：" + value);
        }
    }
}

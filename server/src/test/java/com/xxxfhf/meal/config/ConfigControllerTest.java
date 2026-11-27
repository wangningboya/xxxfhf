package com.xxxfhf.meal.config;

import com.xxxfhf.meal.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 后台保存配置时的值内容校验（技术文档 3.3 的 {@code POST /admin/config}）。
 *
 * <p>这几个值写坏了**不会报错**，只会被读取侧静默回落：截单回 21:00（首页却原样展示脏值）、
 * 配送费回 0（变免费配送）、释放分钟回 30、取消超时回 0（= 关闭自动同意）、
 * 自取时段回空（自取单永远「请选择取餐时段」，全下不了）。商家只会看到「已保存」，
 * 所以宁可在保存时 400 掉。这里钉的就是这道 400。
 *
 * <p>只验校验分支：{@link ConfigService} 整个 mock 掉，不碰数据库。
 */
@ExtendWith(MockitoExtension.class)
class ConfigControllerTest {

    @Mock
    private ConfigService configService;

    private ConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ConfigController(configService, JsonMapper.builder().build());
    }

    /** 只带一个键的最小载荷，省去拼整张配置表 */
    private static Map<String, String> single(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    @Test
    void badCutoffFormatIsRejected() {
        // 「晚上9点」这种中文写法 ServeDateClock 解析不了，会静默按 21:00 截单
        assertThatThrownBy(() -> controller.save(single(ConfigKey.CUTOFF_TIME, "晚上9点")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("截单时间必须是 HH:mm");

        verify(configService, never()).setAll(any());
    }

    @Test
    void cutoffOutOfRangeIsRejected() {
        // 格式对但小时越界：LocalTime.parse 会抛，ServeDateClock 同样回落到 21:00
        assertThatThrownBy(() -> controller.save(single(ConfigKey.CUTOFF_TIME, "25:00")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("截单时间必须在 00:00 到 23:59 之间");

        verify(configService, never()).setAll(any());
    }

    @Test
    void nonNumericDeliveryFeeIsRejected() {
        // 非数字会被 getInt 回落成 0 = 免配送费，属于「少收钱且无提示」
        assertThatThrownBy(() -> controller.save(single(ConfigKey.DELIVERY_FEE, "五元")))
                .isInstanceOf(BizException.class)
                .hasMessage("配送费必须是非负整数，当前值：五元");

        verify(configService, never()).setAll(any());
    }

    @Test
    void nonArrayPickupSlotsIsRejected() {
        // 不是数组 → getStringList 返回空列表 → 自取单没有时段可选
        assertThatThrownBy(() -> controller.save(single(ConfigKey.PICKUP_SLOTS, "{\"a\":1}")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("自取时段必须是 JSON 字符串数组");

        verify(configService, never()).setAll(any());
    }

    @Test
    void validValuesAreSaved() {
        // 「9:00」不补零是允许的（与 ServeDateClock 的兼容口径一致）；
        // 时段只校验是不是 JSON 字符串数组，行内写「午餐 12:00-12:30」也放行
        Map<String, String> values = values(
                ConfigKey.CUTOFF_TIME, "9:00",
                ConfigKey.PICKUP_SLOTS, "[\"11:00-11:30\",\"午餐 12:00-12:30\"]",
                ConfigKey.DELIVERY_FEE, "500",
                ConfigKey.PREPAY_RELEASE_MINUTES, "30",
                ConfigKey.CANCEL_REQUEST_TIMEOUT_HOURS, "0",
                ConfigKey.SHOP_PHONE, "");

        controller.save(values);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.captor();
        verify(configService).setAll(captor.capture());
        assertThat(captor.getValue()).containsEntry(ConfigKey.CUTOFF_TIME, "9:00");
    }

    private static Map<String, String> values(String... keyValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}

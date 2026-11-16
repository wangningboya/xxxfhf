package com.xxxfhf.meal.menu;

import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 供餐日与截单时刻的唯一判定入口。
 *
 * 三件事必须只有一处实现，否则「页面说能下单、接口说已截单」这类矛盾迟早出现：
 * 1. 今天 / 明天 / 后天是哪天
 * 2. 某供餐日是否已过截单时刻
 * 3. 某供餐日是否被一键停业
 *
 * **一律以服务端时间为准**，客户端时间只用于展示（需求 4.4、第六章「截单瞬间下单」）。
 */
@Component
public class ServeDateClock {

    private static final Logger log = LoggerFactory.getLogger(ServeDateClock.class);

    /** 截单时间读不到或格式不对时的兜底，与建表初始数据保持一致。 */
    private static final LocalTime DEFAULT_CUTOFF = LocalTime.of(21, 0);

    private final ConfigService configService;

    public ServeDateClock(ConfigService configService) {
        this.configService = configService;
    }

    public LocalDate today() {
        return LocalDate.now();
    }

    public LocalDate tomorrow() {
        return today().plusDays(1);
    }

    public LocalDate dayAfterTomorrow() {
        return today().plusDays(2);
    }

    /**
     * 全局截单时刻（只有一个 HH:mm，全天所有日期共用）。
     *
     * 兼容「9:00」这种不补零的写法：LocalTime.parse 只认「09:00」，
     * 而商家在配置页手填时很容易写成前者，直接抛异常会让整个点餐页打不开。
     */
    public LocalTime cutoffTime() {
        String raw = configService.get(ConfigKey.CUTOFF_TIME, "21:00").trim();
        String normalized = raw.length() == 4 && raw.charAt(1) == ':' ? "0" + raw : raw;
        try {
            return LocalTime.parse(normalized);
        } catch (DateTimeParseException ex) {
            log.warn("截单时间配置不合法，值={}，按默认 {} 处理", raw, DEFAULT_CUTOFF);
            return DEFAULT_CUTOFF;
        }
    }

    /**
     * 截单时刻 = 供餐日 − 1 天 + 配置的 HH:mm。
     *
     * 用「供餐日减一天」直接算，不依赖服务启动时间，所以跨零点、跨月、跨年都自然正确。
     */
    public LocalDateTime cutoffAt(LocalDate serveDate) {
        return LocalDateTime.of(serveDate.minusDays(1), cutoffTime());
    }

    public boolean isCutoff(LocalDate serveDate) {
        return !LocalDateTime.now().isBefore(cutoffAt(serveDate));
    }

    /** 一键停业按供餐日写入，与该日是否已建菜单无关。 */
    public boolean isClosed(LocalDate serveDate) {
        return configService.getStringList(ConfigKey.CLOSED_DATES).contains(serveDate.toString());
    }

    /** 用户端最多只展示两个供餐日：明天与后天。更远的日期商家勾了也不展示。 */
    public boolean isUserVisible(LocalDate serveDate) {
        return serveDate.equals(tomorrow()) || serveDate.equals(dayAfterTomorrow());
    }

    /**
     * 能否下单。
     *
     * 两层判断不能混：**日期**决定「是不是可下单的那一天」（只有明天可以），
     * **截单**决定「今天这个时间点还来不来得及」。后天的菜单即使没截单也只能看。
     */
    public boolean isOrderable(LocalDate serveDate) {
        return serveDate.equals(tomorrow()) && !isCutoff(serveDate) && !isClosed(serveDate);
    }
}

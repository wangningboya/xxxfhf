package com.xxxfhf.meal.menu;

import com.xxxfhf.meal.common.BizException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 供餐日期参数解析。
 *
 * 不用 Spring 的自动类型转换：`?serveDate=abc` 走到类型转换失败时抛的是
 * MethodArgumentTypeMismatchException，全局处理器没有对应分支就会变成 500，
 * 排查的人会以为是服务端 bug。这里统一转成人话。
 */
public final class DateParam {

    private DateParam() {
    }

    /** 空值返回 null（表示「按默认日期」），非空必须是 yyyy-MM-dd。 */
    public static LocalDate parse(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw BizException.param(fieldName + " 格式应为 yyyy-MM-dd，当前值：" + raw);
        }
    }
}

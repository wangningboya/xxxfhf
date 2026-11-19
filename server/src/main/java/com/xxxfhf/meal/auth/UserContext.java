package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;

/**
 * 请求级登录主体容器。
 *
 * 必须在请求结束时清理（见 AuthInterceptor#afterCompletion），否则线程复用会串号。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 取当前用户，未登录直接抛 401。 */
    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * 取当前用户，没有则返回 null。
     *
     * 定时任务与事件回调里没有请求上下文，写操作日志的人要能「没有操作人」而不是抛 401 ——
     * 那种场景下抛异常会把整批任务带崩。
     */
    public static CurrentUser peek() {
        return HOLDER.get();
    }

    public static Long userId() {
        return require().userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

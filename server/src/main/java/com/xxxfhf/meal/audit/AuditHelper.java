package com.xxxfhf.meal.audit;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xxxfhf.meal.auth.CurrentUser;
import com.xxxfhf.meal.auth.UserContext;

import java.time.LocalDateTime;

/**
 * 操作人与操作来源的统一取值口，以及 lambdaUpdate 的时间戳补丁。
 *
 * <p>为什么不直接用 {@code UserContext.userId()}：定时任务（取消申请超时自动同意、邀请码过期）
 * 没有登录态，那里调 {@code require()} 会抛 401 把整批任务带崩。这里取不到人一律记 {@code 0}，
 * 事后排查时「不是人做的」这件事本身就是线索。
 *
 * <p>为什么系统用 0 而不是 NULL：主键是数据库自增 BIGINT（AUTO_INCREMENT 从 1 开始，0 永远不会
 * 被分配给任何一条记录），所以 0 天然是不会撞的哨兵值；同时 created_by / operator_id 这些列可以
 * 保持 NOT NULL，查询时不必到处判空。
 */
public final class AuditHelper {

    /** 系统代做：定时任务、超时自动处理 */
    public static final Long SYSTEM = 0L;

    public static final String SOURCE_ADMIN = "admin";
    public static final String SOURCE_MINIPROGRAM = "miniprogram";
    public static final String SOURCE_TASK = "task";

    private AuditHelper() {
    }

    /** 当前操作人 user_id，无登录态返回 0。 */
    public static Long actor() {
        CurrentUser user = UserContext.peek();
        return user == null || user.userId() == null ? SYSTEM : user.userId();
    }

    /** 操作来源：网页后台 / 小程序 / 定时任务。 */
    public static String source() {
        CurrentUser user = UserContext.peek();
        if (user == null) {
            return SOURCE_TASK;
        }
        return user.isMerchant() ? SOURCE_ADMIN : SOURCE_MINIPROGRAM;
    }

    /**
     * 给 {@code update(null, wrapper)} 这类更新补上 updated_at / updated_by。
     *
     * <p>必须补的原因：MyBatis-Plus 的自动填充只在「传了实体」的
     * {@code update(entity, wrapper)} / {@code updateById(entity)} 上触发。项目里大量更新是
     * {@code update(null, Wrappers.lambdaUpdate().set(...))} —— 不传实体就没有对象可填，
     * 那几列的 updated_by 会一直是空，而空值不会被任何人发现。
     *
     * <p>用 {@code setSql("updated_at = {0}", ...)} 而不是拼字符串：
     * {@code {0}} 会被替换成预编译占位符，操作人即使是奇怪字符也注入不进来。
     */
    public static <T> LambdaUpdateWrapper<T> stamp(LambdaUpdateWrapper<T> wrapper) {
        return wrapper
                .setSql("updated_at = {0}", LocalDateTime.now())
                .setSql("updated_by = {0}", actor());
    }
}

package com.xxxfhf.meal.invite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xxxfhf.meal.invite.domain.InviteCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {

    /**
     * 原子核销：扣一次次数。
     *
     * 条件里同时带「状态还能用」「未过期」「还有剩余次数」，由 MySQL 单条语句保证不会超发：
     * 二十个人同时提交，只有剩余次数够的那几个能拿到 affectedRows = 1，其余返回 0 后按失效提示。
     * 禁止「先查再改」，那会并发超发。
     *
     * 扣完最后一次时状态直接落 used，避免出现「used_count 已到上限但状态还是 partial」的中间态。
     *
     * <p><b>赋值顺序不能调</b>：MySQL 单表 UPDATE 的 SET 子句从左到右依次求值，
     * 后面的赋值会读到前面已经改过的列。所以 `status` 必须写在 `used_count` 之前，
     * 它读到的才是「本次核销之前」的次数；反过来写（先 used_count 再加 1，CASE 里又 +1）
     * 会多加一次，3 次的码用 2 次就被标成用完。
     */
    @Update("""
            UPDATE invite_code
               SET status = CASE WHEN used_count + 1 >= max_uses THEN 'used' ELSE 'partial' END,
                   used_count = used_count + 1,
                   last_used_at = NOW()
             WHERE code = #{code}
               AND status IN ('unused', 'partial')
               AND expire_at > NOW()
               AND used_count < max_uses
            """)
    int consume(@Param("code") String code);

    /**
     * 把已过有效期但仍标 unused / partial 的码批量标成 expired。由扫描任务与列表查询调用。
     *
     * 只改状态不动次数：剩余次数随过期一起作废，但「用过几次」是历史事实，不能抹掉。
     */
    @Update("""
            UPDATE invite_code
               SET status = 'expired'
             WHERE status IN ('unused', 'partial')
               AND expire_at <= NOW()
            """)
    int markExpired();

    /**
     * 商家还没查看过的「码被用过」条数，用于后台红点。
     *
     * 口径是「码」不是「次」：一个码被 20 个人用也只算 1 条，否则商家一打开就是 20 个红点。
     * 走 idx_invite_read。
     */
    @Select("""
            SELECT COUNT(*)
              FROM invite_code
             WHERE used_count > 0
               AND read_at IS NULL
            """)
    long countUnread();

    /** 商家打开邀请码管理时把提醒清掉；不影响码本身的状态。 */
    @Update("""
            UPDATE invite_code
               SET read_at = NOW()
             WHERE used_count > 0
               AND read_at IS NULL
            """)
    int markRead();
}

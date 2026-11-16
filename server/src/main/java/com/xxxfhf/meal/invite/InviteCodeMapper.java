package com.xxxfhf.meal.invite;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {

    /**
     * 原子核销。
     *
     * 条件里同时带 status='unused' 与 expire_at > NOW()，由 MySQL 单条语句保证「同一码只有一个人能用」：
     * 两个用户同时提交，只有一个能拿到 affectedRows = 1，另一个返回 0 后按「已失效」提示。
     * 禁止「先查再改」，那会并发双开。
     */
    @Update("""
            UPDATE invite_code
               SET status = 'used', used_by = #{userId}, used_at = NOW()
             WHERE code = #{code}
               AND status = 'unused'
               AND expire_at > NOW()
            """)
    int consume(@Param("code") String code, @Param("userId") String userId);

    /** 把已过有效期但仍标 unused 的码批量标成 expired。由扫描任务调用，不影响核销判定。 */
    @Update("""
            UPDATE invite_code
               SET status = 'expired'
             WHERE status = 'unused'
               AND expire_at <= NOW()
            """)
    int markExpired();
}

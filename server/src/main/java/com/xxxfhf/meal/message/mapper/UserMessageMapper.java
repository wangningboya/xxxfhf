package com.xxxfhf.meal.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xxxfhf.meal.message.domain.UserMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface UserMessageMapper extends BaseMapper<UserMessage> {

    /**
     * 清理：删除某个时间点之前的消息，每次最多 {@code limit} 条。
     *
     * <p>写成注解 SQL 而不是 {@code Wrappers} + {@code last("LIMIT n")}，是因为这里
     * 唯一的调用方就是定时任务，SQL 长什么样应当一眼看得见 —— 分批删除的 {@code LIMIT}
     * 一旦被误写成一次性删除，大表上会长时间锁表。
     */
    @Delete("DELETE FROM user_message WHERE created_at < #{before} LIMIT #{limit}")
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}

package com.xxxfhf.meal.invite;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 邀请码使用明细。
 *
 * 只做 insert 与按码 / 按人查询，没有条件更新——「别超发」由 {@link InviteCodeMapper#consume} 那条 UPDATE 保证。
 * 这里不加自定义 SQL，统一走 Wrappers，列名改动能被编译期发现。
 */
@Mapper
public interface InviteUseMapper extends BaseMapper<InviteUse> {
}

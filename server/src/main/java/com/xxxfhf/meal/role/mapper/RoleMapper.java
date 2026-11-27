package com.xxxfhf.meal.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xxxfhf.meal.role.domain.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 每个角色被多少个账号引用。role_ids 是 JSON 数组，用 JSON_CONTAINS 判断。
     * SQL 里只有参数占位，没有拼接，不存在注入面。
     *
     * <p>候选值用 <code>CAST(r.id AS CHAR)</code> 而不是 <code>JSON_QUOTE(r.id)</code>：
     * 主键已是 BIGINT，JSON_QUOTE 只接受字符串参数（MySQL 3064 Incorrect type for
     * argument 1），而 role_ids 里存的是数字 <code>[1]</code> 不是 <code>["1"]</code>，
     * 所以候选值必须是能解析成 JSON 数字的 <code>1</code>，不是带引号的 <code>"1"</code>。
     */
    @Select("""
            SELECT r.id AS roleId, COUNT(u.id) AS memberCount
              FROM `role` r
              LEFT JOIN `user` u
                     ON u.user_type = 'merchant'
                    AND JSON_CONTAINS(COALESCE(u.role_ids, JSON_ARRAY()), CAST(r.id AS CHAR))
             GROUP BY r.id
            """)
    List<Map<String, Object>> countMembers();
}

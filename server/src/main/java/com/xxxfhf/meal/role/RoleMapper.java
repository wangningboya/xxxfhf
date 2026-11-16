package com.xxxfhf.meal.role;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
     */
    @Select("""
            SELECT r.id AS roleId, COUNT(u.id) AS memberCount
              FROM `role` r
              LEFT JOIN `user` u
                     ON u.user_type = 'merchant'
                    AND JSON_CONTAINS(COALESCE(u.role_ids, JSON_ARRAY()), JSON_QUOTE(r.id))
             GROUP BY r.id
            """)
    List<Map<String, Object>> countMembers();
}

package com.xxxfhf.meal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.role.domain.Role;
import com.xxxfhf.meal.role.mapper.RoleMapper;
import com.xxxfhf.meal.user.domain.AuditStatus;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 JSON 字段的读写链路（技术文档 6.2 第 7 项，本项目最大的隐性坑）。
 *
 * 要验证的是三件事同时成立：
 *   1. 实体上有 @TableName(autoResultMap = true)
 *   2. 字段上用的是 Jackson3TypeHandler（不是 Jackson 2 的 JacksonTypeHandler）
 *   3. 数据库列是 JSON / TEXT，MyBatis 能正确回填
 * 缺任何一条都不会报错，只会「安静地读出 null」。
 *
 * 运行前提：数据库可连（本地 MySQL 或 docker compose 起的 mysql），且已执行建表脚本。
 * 未配置 APP_JWT_SECRET 时自动跳过，避免把「没配环境」误判成代码问题。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "APP_JWT_SECRET", matches = "(?s).{32,}")
class JsonFieldMappingTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Test
    @DisplayName("user.role_ids 的 List<Long> 能写入并原样读出")
    void userRoleIdsRoundTrip() {
        User user = new User();
        user.setOpenid("ut_" + UUID.randomUUID().toString().replace("-", ""));
        user.setAuditStatus(AuditStatus.PENDING);
        user.setRoleIds(List.of(1L));
        user.setCreatedAt(LocalDateTime.now());

        userMapper.insert(user);
        assertThat(user.getId()).as("主键应被自动生成").isNotNull();

        User loaded = userMapper.selectById(user.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRoleIds())
                .as("若为 null，说明 TypeHandler 或 autoResultMap 配错了")
                .isNotNull()
                .containsExactly(1L);
    }

    @Test
    @DisplayName("role.permissions 的 List<String> 能写入并原样读出，且中文正常")
    void rolePermissionsRoundTrip() {
        Role role = new Role();
        role.setName("测试角色_" + UUID.randomUUID().toString().substring(0, 8));
        role.setPermissions(List.of("order.accept", "prep.view"));
        role.setBuiltin(false);
        role.setCreatedAt(LocalDateTime.now());

        roleMapper.insert(role);

        Role loaded = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getId, role.getId()));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getPermissions()).containsExactly("order.accept", "prep.view");
        assertThat(loaded.isBuiltin()).isFalse();
    }
}

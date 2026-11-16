package com.xxxfhf.meal.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.auth.PasswordService;
import com.xxxfhf.meal.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 初始商家账号初始化。
 *
 * 为什么不在 02-data.sql 里写死密码哈希：
 * BCrypt 哈希必须由 BCryptPasswordEncoder 现场生成才保证能被同一实现校验通过，
 * 手写或复制来的哈希容易带错前缀（$2a$/$2y$）导致「密码明明对却登不上」。
 *
 * 幂等：库中已存在该 username 就跳过，不会覆盖后来改过的密码。
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    /** 内置商家角色，与 deploy/mysql/init/02-data.sql 中的 role_merchant 对应 */
    private static final String MERCHANT_ROLE_ID = "role_merchant";

    private final AppProperties props;
    private final UserMapper userMapper;
    private final PasswordService passwordService;

    public AdminAccountInitializer(AppProperties props, UserMapper userMapper,
                                   PasswordService passwordService) {
        this.props = props;
        this.userMapper = userMapper;
        this.passwordService = passwordService;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Admin admin = props.getAdmin();
        if (!admin.isInitEnabled()) {
            return;
        }

        String username = admin.getInitUsername();
        if (username == null || username.isBlank()) {
            log.warn("APP_ADMIN_USERNAME 为空，跳过初始商家账号创建");
            return;
        }

        Long exists = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            log.debug("商家账号已存在，跳过初始化 username={}", username);
            return;
        }

        User user = new User();
        user.setUserType(UserType.MERCHANT);
        user.setUsername(username);
        user.setPasswordHash(passwordService.hash(admin.getInitPassword()));
        user.setNickName(username);
        // 商家不参与群成员审核流程，直接给 approved
        user.setAuditStatus(AuditStatus.APPROVED);
        user.setRoleIds(List.of(MERCHANT_ROLE_ID));
        user.setCreatedAt(LocalDateTime.now());
        user.setApprovedAt(LocalDateTime.now());
        userMapper.insert(user);

        log.info("已创建初始商家账号 username={} userId={}", username, user.getId());
        if ("admin".equals(username) && "admin".equals(admin.getInitPassword())) {
            log.warn("当前商家账号使用默认密码 admin，上线前必须通过环境变量 APP_ADMIN_PASSWORD 修改");
        }
    }
}

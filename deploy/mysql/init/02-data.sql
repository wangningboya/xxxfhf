-- =============================================================================
-- 初始化数据（幂等，可重复执行）
--
-- 1) 内置「商家」角色：权限不可被剥夺，故写死全部权限项
-- 2) 系统配置初始值：截单时间等由商家在后台随时修改，这里只是让系统能跑起来
--
-- 注意：商家登录账号（默认 admin）不在这里插入。
-- 密码哈希由应用启动时的 AdminAccountInitializer 用 BCryptPasswordEncoder 现场生成，
-- 避免手写哈希前缀（$2a$/$2b$/$2y$）不一致导致「密码对却登不上」。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 内置商家角色（id 固定，勿改：user.role_ids 会引用它）
-- -----------------------------------------------------------------------------
INSERT INTO `role` (`id`, `name`, `permissions`, `builtin`)
VALUES ('role_merchant', '商家',
        JSON_ARRAY(
                'dish.manage', 'combo.manage', 'menu.manage',
                'user.audit', 'user.view',
                'region.manage', 'user.address',
                'order.accept', 'order.view',
                'prep.view', 'prep.export',
                'order.payment', 'finance.view',
                'config.manage', 'role.manage'
        ),
        1)
ON DUPLICATE KEY UPDATE `permissions` = VALUES(`permissions`), `builtin` = VALUES(`builtin`);

-- -----------------------------------------------------------------------------
-- 配置项
-- -----------------------------------------------------------------------------
INSERT INTO `config` (`config_key`, `config_value`, `remark`)
VALUES ('cutoffTime', '21:00', '截单时间 HH:mm，供餐日前一天生效，全局一套'),
       ('serveTimeText', '午饭前', '供餐时段承诺文案，展示在点餐页与订单上'),
       ('pickupSlots', '["11:00-11:30","11:30-12:00"]', '自取可选时段，全局一套，不限每时段份数'),
       ('deliveryFee', '0', '配送费（分），0 表示免配送费'),
       ('shopName', '', '店铺名称，首页展示'),
       ('shopNotice', '', '店铺公告，首页滚动展示'),
       ('phoneAuthEnabled', 'false', '是否启用微信手机号授权'),
       ('prepayReleaseMinutes', '30', '未付款订单释放份数的等待分钟数'),
       ('closedDates', '[]', '停业的供餐日列表 ["2026-12-01"]'),
       ('groupOpenIds', '[]', '允许进入本店的微信群 openGId 白名单（JSON 数组）；空数组表示只允许邀请码进入'),
       ('subscribeTemplates', '{}', '订阅消息模板（JSON 对象）；空对象表示未申请模板、不发通知。结构见技术文档')
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;

-- =============================================================================
-- 开发库结构补齐（一次性，仅用于**已经存在**的开发 / 测试库）
--
-- 为什么会有这个文件：
--   `init/01-schema.sql` 用的是 `CREATE TABLE IF NOT EXISTS`，表已存在时改定义不生效。
--   N-14 / N-15 新增了 3 个列、1 个索引、2 张表，已经建过库的环境要手动补一次。
--
-- 为什么不直接改 init 脚本重跑：
--   会丢掉库里已有的测试账号与订单，用户得重新走一遍准入审核。
--
-- 生产怎么用：
--   **不要执行本文件**。生产是全新装库，直接跑 init/01-schema.sql + 02-data.sql，
--   结构已经是最新的。这个文件只解决「老库追上最新结构」。
--
-- 幂等：每一步都先查 INFORMATION_SCHEMA，重复执行不会报错。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. address.reject_reason（地址驳回原因）
-- -----------------------------------------------------------------------------
SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address'
            AND COLUMN_NAME = 'reject_reason') = 0,
        'ALTER TABLE `address` ADD COLUMN `reject_reason` VARCHAR(200) DEFAULT NULL COMMENT ''驳回原因，status=rejected 时有值'' AFTER `status`',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2. order.cancel_requested_at（取消申请提交时间，超时自动同意按此列扫描）
-- -----------------------------------------------------------------------------
SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order'
            AND COLUMN_NAME = 'cancel_requested_at') = 0,
        'ALTER TABLE `order` ADD COLUMN `cancel_requested_at` DATETIME DEFAULT NULL COMMENT ''取消申请提交时间'' AFTER `cancel_request`',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3. order 上的取消申请扫描索引
-- -----------------------------------------------------------------------------
SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order'
            AND INDEX_NAME = 'idx_order_cancel_scan') = 0,
        'ALTER TABLE `order` ADD KEY `idx_order_cancel_scan` (`cancel_requested`, `status`, `cancel_requested_at`)',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 4. 字典两张表（定义与 init/01-schema.sql 第 12、13 节完全一致）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `dict_type` (
    `type_code`  VARCHAR(64)  NOT NULL COMMENT '类型编码，代码里按它取值，如 order_status',
    `type_name`  VARCHAR(64)  NOT NULL COMMENT '类型名称，如「订单状态」',
    `builtin`    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 内置类型（项不可增删），0 自定义',
    `remark`     VARCHAR(255)          DEFAULT NULL COMMENT '说明',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`type_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='字典类型';

CREATE TABLE IF NOT EXISTS `dict_item` (
    `id`         VARCHAR(32)  NOT NULL COMMENT '主键',
    `type_code`  VARCHAR(64)  NOT NULL COMMENT '所属类型',
    `item_value` VARCHAR(64)  NOT NULL COMMENT '存值，写进库的就是它，如 pending_accept',
    `item_label` VARCHAR(64)  NOT NULL COMMENT '显示名，如「待接单」',
    `tag_type`   VARCHAR(16)           DEFAULT NULL COMMENT '前端配色 info/success/warning/danger',
    `sort`       INT          NOT NULL DEFAULT 0 COMMENT '排序，小的在前',
    `status`     VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active 启用 / disabled 停用',
    `builtin`    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 内置项（不可删除，只能改显示名）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dict_item` (`type_code`, `item_value`),
    KEY `idx_dict_item_type` (`type_code`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='字典项';

-- -----------------------------------------------------------------------------
-- 5. 新配置项：取消申请超时自动同意（0 = 关闭）
-- -----------------------------------------------------------------------------
INSERT INTO `config` (`config_key`, `config_value`, `remark`)
VALUES ('cancelRequestTimeoutHours', '2', '取消申请超时自动同意的等待小时数，0 表示不自动处理')
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;

-- -----------------------------------------------------------------------------
-- 6. 内置角色补 dict.manage 权限
-- -----------------------------------------------------------------------------
UPDATE `role`
   SET `permissions` = JSON_ARRAY_APPEND(`permissions`, '$', 'dict.manage')
 WHERE `id` = 'role_merchant'
   AND NOT JSON_CONTAINS(`permissions`, '"dict.manage"');

-- -----------------------------------------------------------------------------
-- 7. 字典初始数据（与 init/02-data.sql 保持一致）
-- -----------------------------------------------------------------------------
INSERT INTO `dict_type` (`type_code`, `type_name`, `builtin`, `remark`)
VALUES ('order_status', '订单状态', 1, '与 OrderStatus 常量一一对应'),
       ('pay_status', '收款状态', 1, '与 PayStatus 常量一一对应'),
       ('address_status', '地址状态', 1, 'active 可用 / pending 待确认 / rejected 已驳回'),
       ('audit_status', '用户审核状态', 1, '与 AuditStatus 常量一一对应'),
       ('invite_status', '邀请码状态', 1, 'unused / used / expired / revoked'),
       ('delivery_type', '取送方式', 1, 'pickup 自取 / delivery 配送'),
       ('notify_type', '通知类型', 1, '与 NotifyType 一一对应，模板未申请时不发送')
ON DUPLICATE KEY UPDATE `type_code` = `type_code`;

INSERT INTO `dict_item` (`id`, `type_code`, `item_value`, `item_label`, `tag_type`, `sort`, `status`, `builtin`)
VALUES ('di-ord-01', 'order_status', 'pending_accept', '待接单', 'warning', 10, 'active', 1),
       ('di-ord-02', 'order_status', 'accepted', '已接单', 'primary', 20, 'active', 1),
       ('di-ord-04', 'order_status', 'ready', '待取餐', 'success', 30, 'active', 1),
       ('di-ord-05', 'order_status', 'completed', '已完成', 'success', 50, 'active', 1),
       ('di-ord-06', 'order_status', 'cancelled', '已取消', 'info', 60, 'active', 1),
       ('di-ord-07', 'order_status', 'rejected', '已拒单', 'danger', 70, 'active', 1),
       ('di-pay-01', 'pay_status', 'unpaid', '未收款', 'info', 10, 'active', 1),
       ('di-pay-02', 'pay_status', 'claimed', '用户声称已付款', 'warning', 20, 'active', 1),
       ('di-pay-03', 'pay_status', 'paid', '已确认收款', 'success', 30, 'active', 1),
       ('di-pay-04', 'pay_status', 'refunding', '待退款', 'danger', 40, 'active', 1),
       ('di-pay-05', 'pay_status', 'refunded', '已退款', 'info', 50, 'active', 1),
       ('di-addr-01', 'address_status', 'active', '可用', 'success', 10, 'active', 1),
       ('di-addr-02', 'address_status', 'pending', '待商家确认', 'warning', 20, 'active', 1),
       ('di-addr-03', 'address_status', 'rejected', '已驳回', 'danger', 30, 'active', 1),
       ('di-audit-01', 'audit_status', 'pending', '待审核', 'warning', 10, 'active', 1),
       ('di-audit-02', 'audit_status', 'approved', '已通过', 'success', 20, 'active', 1),
       ('di-audit-03', 'audit_status', 'rejected', '已驳回', 'danger', 30, 'active', 1),
       ('di-audit-04', 'audit_status', 'disabled', '已停用', 'info', 40, 'active', 1),
       ('di-inv-01', 'invite_status', 'unused', '未使用', 'success', 10, 'active', 1),
       ('di-inv-02', 'invite_status', 'used', '已使用', 'info', 20, 'active', 1),
       ('di-inv-03', 'invite_status', 'expired', '已过期', 'info', 30, 'active', 1),
       ('di-inv-04', 'invite_status', 'revoked', '已作废', 'danger', 40, 'active', 1),
       ('di-deliv-01', 'delivery_type', 'pickup', '自取', 'primary', 10, 'active', 1),
       ('di-deliv-02', 'delivery_type', 'delivery', '配送', 'success', 20, 'active', 1),
       ('di-notify-01', 'notify_type', 'newOrder', '新订单提醒', 'warning', 10, 'active', 1),
       ('di-notify-02', 'notify_type', 'accepted', '订单已接单', 'success', 20, 'active', 1),
       ('di-notify-03', 'notify_type', 'rejected', '订单被拒', 'danger', 30, 'active', 1),
       ('di-notify-04', 'notify_type', 'ready', '可以取餐了', 'success', 40, 'active', 1),
       ('di-notify-05', 'notify_type', 'cancelled', '订单已取消', 'info', 50, 'active', 1),
       ('di-notify-06', 'notify_type', 'cancelRequest', '用户申请取消', 'danger', 15, 'active', 1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- -----------------------------------------------------------------------------
-- 8. 清掉已废弃的配置项 phoneAuthEnabled（N-18）
--
-- 手机号改为只走手填，微信一键授权连同它的开关一起下线。老库里这条配置留着也不会
-- 被读取（ConfigService.KNOWN_KEYS 已移除），但留着会让人以为功能还在，顺手删掉。
-- -----------------------------------------------------------------------------
DELETE FROM `config` WHERE `config_key` = 'phoneAuthEnabled';

-- -----------------------------------------------------------------------------
-- 9. 内置角色补 user.delete 权限（N-20：用户审核支持物理删除）
-- -----------------------------------------------------------------------------
-- 删除是不可撤销的动作，单独拆一个权限项，不跟日常审核的 user.audit 混在一起。
-- 新装库由 init/02-data.sql 的 role 种子行直接带上；老库跑这一句补齐。
UPDATE `role`
   SET `permissions` = JSON_ARRAY_APPEND(`permissions`, '$', 'user.delete')
 WHERE `id` = 'role_merchant'
   AND NOT JSON_CONTAINS(`permissions`, '"user.delete"');

-- -----------------------------------------------------------------------------
-- 10. 去掉「制作中」，配送中并回 ready（N-23）
--
-- 接单后不再有中间态：已接单 → ready → 已完成，一条直线。
-- ready 在界面上按取送方式换说法：自取「待取餐」、配送「配送中」——
-- 文案由 order.delivery_type 现算，不占新的状态值，库里也就没有两套数据要对齐。
--
-- 字典只留 ready 一项（把 making 删掉，ready 的 sort 提到 30 补它的位置）。
-- 早期版本若已写入 delivering 字典项、或配送单已被迁到 delivering，
-- 这里一并收拾回 ready。整段可重复执行。
-- -----------------------------------------------------------------------------
DELETE FROM `dict_item`
 WHERE `type_code` = 'order_status'
   AND `item_value` IN ('making', 'delivering');

UPDATE `dict_item`
   SET `item_label` = '待取餐',
       `tag_type`   = 'success',
       `sort`       = 30,
       `status`     = 'active'
 WHERE `type_code` = 'order_status'
   AND `item_value` = 'ready';

-- 历史订单状态归一：making 与 delivering 都不在状态机上，全部回到 ready
UPDATE `order` SET `status` = 'ready'
 WHERE `status` IN ('making', 'delivering');

-- -----------------------------------------------------------------------------
-- 11. address 审计四列 + audit_log 表 + audit.view 权限（N-27）
--
-- 本轮只给 address 铺审计列（试点），其余 12 张表下一轮批量铺 —— 那批要动
-- OrderService 与 22 处 lambdaUpdate，和界面改造混在一轮不好定位问题。
--
-- 存量 address 的 created_at 只能填执行时刻：历史提交时间没有记录，不可考。
-- 好在库里原本只有个位数地址，且 pending 队列从这一刻开始才有意义。
-- -----------------------------------------------------------------------------
SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address'
            AND COLUMN_NAME = 'created_at') = 0,
        'ALTER TABLE `address` ADD COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间（用户提交时刻）'' AFTER `reject_reason`',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address'
            AND COLUMN_NAME = 'created_by') = 0,
        'ALTER TABLE `address` ADD COLUMN `created_by` VARCHAR(32) DEFAULT NULL COMMENT ''创建人 user_id，系统写 system'' AFTER `created_at`',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address'
            AND COLUMN_NAME = 'updated_at') = 0,
        'ALTER TABLE `address` ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''最后修改时间'' AFTER `created_by`',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address'
            AND COLUMN_NAME = 'updated_by') = 0,
        'ALTER TABLE `address` ADD COLUMN `updated_by` VARCHAR(32) DEFAULT NULL COMMENT ''最后修改人 user_id，系统写 system'' AFTER `updated_at`',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 待办队列的默认查询是 status='pending' 按 created_at 正序
SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address'
            AND INDEX_NAME = 'idx_address_status_created') = 0,
        'ALTER TABLE `address` ADD KEY `idx_address_status_created` (`status`, `created_at`)',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 操作记录表：建表语句与 init/01-schema.sql 第 14 节完全一致，改一处要同步另一处
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`          VARCHAR(32)  NOT NULL COMMENT '主键',
    `table_name`  VARCHAR(32)  NOT NULL COMMENT '对象表：address / user / order',
    `record_id`   VARCHAR(32)  NOT NULL COMMENT '对象主键',
    `action`      VARCHAR(32)  NOT NULL COMMENT '动作码，如 address.approve / order.accept',
    `summary`     VARCHAR(500) NOT NULL COMMENT '一句话说明，人读的，含关键字段前后值',
    `operator_id` VARCHAR(32)  NOT NULL COMMENT '操作人 user_id；定时任务写 system',
    `operator_name` VARCHAR(64)         DEFAULT NULL COMMENT '操作人当时的昵称/账号快照，改名后仍可读',
    `source`      VARCHAR(16)  NOT NULL COMMENT 'admin 网页后台 / miniprogram 小程序 / task 定时任务',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (`id`),
    KEY `idx_audit_record` (`table_name`, `record_id`),
    KEY `idx_audit_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='操作记录';

-- 新的「操作记录」菜单要 audit.view 才看得到
UPDATE `role`
   SET `permissions` = JSON_ARRAY_APPEND(`permissions`, '$', 'audit.view')
 WHERE `id` = 'role_merchant'
   AND NOT JSON_CONTAINS(`permissions`, '"audit.view"');

-- -----------------------------------------------------------------------------
-- 第 12 步（N-28）：地址手机号改必填
--   1) 先按归属账号的手机号回填历史空值；
--   2) 仍有空值的直接报错中断——必须人工补齐后重跑，不允许带着 NULL 过 NOT NULL；
--   3) 幂等：列已 NOT NULL 则跳过。
-- -----------------------------------------------------------------------------
UPDATE `address` a
  JOIN `user` u ON u.`id` = a.`user_id`
   SET a.`phone` = TRIM(u.`phone`)
 WHERE (a.`phone` IS NULL OR a.`phone` = '')
   AND u.`phone` IS NOT NULL AND TRIM(u.`phone`) != '';

-- 把回填后仍为空的地址亮出来，人工决定补什么
SELECT a.`id`, a.`user_id`, a.`region_name`, a.`detail`
  FROM `address` a
 WHERE a.`phone` IS NULL OR a.`phone` = '';

SET @null_count = (SELECT COUNT(*) FROM `address` WHERE `phone` IS NULL OR `phone` = '');
SET @ddl = (
    SELECT IF(
        @null_count > 0,
        'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''仍有地址手机号为空，请按上方 SELECT 清单人工补齐后重跑本脚本''',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        (SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address'
            AND COLUMN_NAME = 'phone') = 'YES',
        'ALTER TABLE `address` MODIFY COLUMN `phone` VARCHAR(20) NOT NULL COMMENT ''联系电话，必填（见需求 4.8）''',
        'DO 0'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

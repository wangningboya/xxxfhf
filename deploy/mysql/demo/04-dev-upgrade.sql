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
       ('di-ord-03', 'order_status', 'making', '制作中', 'warning', 30, 'active', 1),
       ('di-ord-04', 'order_status', 'ready', '待取餐', 'success', 40, 'active', 1),
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

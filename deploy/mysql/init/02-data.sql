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
                'user.audit', 'user.view', 'user.delete',
                'region.manage', 'user.address',
                'order.accept', 'order.view',
                'prep.view', 'prep.export',
                'order.payment', 'finance.view',
                'config.manage', 'role.manage', 'dict.manage',
                'audit.view'
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
       ('prepayReleaseMinutes', '30', '未付款订单释放份数的等待分钟数'),
       ('closedDates', '[]', '停业的供餐日列表 ["2026-12-01"]'),
       ('groupOpenIds', '[]', '允许进入本店的微信群 openGId 白名单（JSON 数组）；空数组表示只允许邀请码进入'),
       ('subscribeTemplates', '{}', '订阅消息模板（JSON 对象）；空对象表示未申请模板、不发通知。结构见技术文档')
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;

-- -----------------------------------------------------------------------------
-- 取消申请超时自动同意（N-14）
--
-- 用户提交退订申请后商家一直不处理，订单会卡在待接单、份数一直被占。
-- 这里给一个兜底时长：超过该小时数仍未处理，系统按「同意」自动取消。
-- 设为 0 表示关闭自动同意（此时只能商家手动处理）。
-- -----------------------------------------------------------------------------
INSERT INTO `config` (`config_key`, `config_value`, `remark`)
VALUES ('cancelRequestTimeoutHours', '2', '取消申请超时自动同意的等待小时数，0 表示不自动处理')
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;

-- -----------------------------------------------------------------------------
-- 字典初始数据（N-14）
--
-- 内置类型的 item 与代码里的枚举一一对应：**只能改显示名 / 颜色 / 排序 / 启停，
-- 不能增删项**，否则库里会出现状态机无法处理的值。
-- -----------------------------------------------------------------------------
INSERT INTO `dict_type` (`type_code`, `type_name`, `builtin`, `remark`)
VALUES ('order_status', '订单状态', 1, '与 OrderStatus 常量一一对应'),
       ('pay_status', '收款状态', 1, '与 PayStatus 常量一一对应'),
       ('address_status', '地址状态', 1, 'active 可用 / pending 待确认 / rejected 已驳回'),
       ('audit_status', '用户审核状态', 1, '与 AuditStatus 常量一一对应'),
       ('invite_status', '邀请码状态', 1, 'unused 未使用 / used 已使用 / expired 已过期 / revoked 已作废'),
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

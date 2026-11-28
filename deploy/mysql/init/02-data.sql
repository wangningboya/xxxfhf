-- =============================================================================
-- 初始化数据（幂等，可重复执行）
--
-- 1) 内置「商家」角色：权限不可被剥夺，故写死全部权限项
-- 2) 系统配置初始值：截单时间等由商家在后台随时修改，这里只是让系统能跑起来
--
-- 主键全部由 AUTO_INCREMENT 生成，脚本不再写死 id：
-- 需要稳定引用的地方改用业务唯一键（role.name / dict_item(type_code,item_value)）。
--
-- 注意：商家登录账号（默认 admin）不在这里插入。
-- 密码哈希由应用启动时的 AdminAccountInitializer 用 BCryptPasswordEncoder 现场生成，
-- 避免手写哈希前缀（$2a$/$2b$/$2y$）不一致导致「密码对却登不上」。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 内置商家角色（按 name 幂等；user.role_ids 存的是自增 id，应用启动时按名称查出来）
-- -----------------------------------------------------------------------------
INSERT INTO `role` (`name`, `permissions`, `builtin`)
VALUES ('商家',
        JSON_ARRAY(
                'dish.manage', 'combo.manage', 'menu.manage',
                'user.audit', 'user.view', 'user.delete', 'user.phone.view',
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
       ('shopAddress', '', '店铺地址（自取点），自取单展示在下单成功页 / 订单详情 / 取餐推送'),
       ('shopPhone', '', '商家联系电话，首页与订单详情可拨号；留空则不显示按钮'),
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
-- 业务限制（商家可在后台「系统配置 → 业务限制」调整，即时生效）
--
-- 三道份数闸互相独立：最多几种管购物车行数、每种几份管单行、总份数管各行之和。
-- 代客下单不读这三项（团餐一次几十份是正常需求），只受当日剩余份数约束。
-- 上限值同时是后台可填的最大值，即只能收紧不能放宽；要放宽需改
-- ConfigController 的 CEIL_* 常量。
-- -----------------------------------------------------------------------------
INSERT INTO `config` (`config_key`, `config_value`, `remark`)
VALUES ('orderMaxItemKinds', '4', '一单最多几种盒饭'),
       ('orderMaxQtyPerItem', '5', '单个盒饭一次最多几份'),
       ('orderMaxQtyTotal', '20', '一单总份数上限（所有明细之和；代客下单不受限）'),
       ('comboDishMaxQty', '99', '盒饭内单个菜品的份数上限'),
       ('orderBatchLimit', '20', '一次批量接单 / 拒单的订单数上限'),
       ('inviteMaxExpireHours', '720', '邀请码有效期上限（小时），720 = 30 天')
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;

-- -----------------------------------------------------------------------------
-- 字典初始数据（N-14）
--
-- 内置类型的 item 与代码里的枚举一一对应：**只能改显示名 / 颜色 / 排序 / 启停，
-- 不能增删项**，否则库里会出现状态机无法处理的值。
--
-- 默认只能改显示名 / 颜色 / 排序 / 启停，不能增删项，否则库里会出现状态机无法处理的值。
--
-- 两个例外（见 DictService.EXTENSIBLE_TYPES）：
--   pay_method    收款方式：只是收款渠道的标签，不参与状态机，商家可自行增删项
--   combo_category 商品分类：盒饭 / 自制菜只是列表分组，不参与任何履约分支，可增项。
--                 种子两项仍是内置项（存值不可改、删不掉，只能停用），商家自建的项可删。
-- 两个类型本身都是内置的、不可删除 —— 一删下拉就空了，历史数据的显示名也一起落空。
--
-- dish_category 是**自定义类型**（builtin = 0）：只建类型、不预置项，
-- 留给将来做「备菜按原料分类分组」时再填，商家现在也可以自己在字典页加项。
--
-- 幂等键是 uk_dict_item(type_code, item_value)：重复执行只校验存在，不覆盖商家改过的显示名。
-- -----------------------------------------------------------------------------
INSERT INTO `dict_type` (`type_code`, `type_name`, `builtin`, `remark`)
VALUES ('order_status', '订单状态', 1, '与 OrderStatus 常量一一对应'),
       ('pay_status', '收款状态', 1, '与 PayStatus 常量一一对应'),
       ('address_status', '地址状态', 1, 'active 可用 / pending 待确认 / rejected 已驳回'),
       ('audit_status', '用户审核状态', 1, '与 AuditStatus 常量一一对应'),
       ('invite_status', '邀请码状态', 1, 'unused 未使用 / partial 部分使用 / used 已用完 / expired 已过期 / revoked 已作废'),
       ('delivery_type', '取送方式', 1, 'pickup 自取 / delivery 配送'),
       ('notify_type', '通知类型', 1, '与 NotifyType 一一对应，模板未申请时不发送'),
       ('pay_method', '收款方式', 1, '初始三项对应 PayMethod 常量；可增删项，仅显示名/排序/启停可改'),
       ('combo_category', '商品分类', 1, '盒饭 / 自制菜等售卖分类；可增项，种子两项只能停用'),
       ('dish_category', '原料分类', 0, '菜品分类，备菜分组预留；自定义类型，不预置项')
ON DUPLICATE KEY UPDATE `type_code` = `type_code`;

INSERT INTO `dict_item` (`type_code`, `item_value`, `item_label`, `tag_type`, `sort`, `status`, `builtin`)
VALUES ('order_status', 'pending_accept', '待接单', 'warning', 10, 'active', 1),
       ('order_status', 'accepted', '已接单', 'primary', 20, 'active', 1),
       ('order_status', 'ready', '待取餐', 'success', 30, 'active', 1),
       ('order_status', 'completed', '已完成', 'success', 50, 'active', 1),
       ('order_status', 'cancelled', '已取消', 'info', 60, 'active', 1),
       ('order_status', 'rejected', '已拒单', 'danger', 70, 'active', 1),
       ('pay_status', 'unpaid', '未收款', 'info', 10, 'active', 1),
       ('pay_status', 'claimed', '用户声称已付款', 'warning', 20, 'active', 1),
       ('pay_status', 'paid', '已确认收款', 'success', 30, 'active', 1),
       ('pay_status', 'refunding', '待退款', 'danger', 40, 'active', 1),
       ('pay_status', 'refunded', '已退款', 'info', 50, 'active', 1),
       ('address_status', 'active', '可用', 'success', 10, 'active', 1),
       ('address_status', 'pending', '待商家确认', 'warning', 20, 'active', 1),
       ('address_status', 'rejected', '已驳回', 'danger', 30, 'active', 1),
       ('audit_status', 'pending', '待审核', 'warning', 10, 'active', 1),
       ('audit_status', 'approved', '已通过', 'success', 20, 'active', 1),
       ('audit_status', 'rejected', '已驳回', 'danger', 30, 'active', 1),
       ('audit_status', 'disabled', '已停用', 'info', 40, 'active', 1),
       ('invite_status', 'unused', '未使用', 'success', 10, 'active', 1),
       ('invite_status', 'partial', '部分使用', 'warning', 15, 'active', 1),
       ('invite_status', 'used', '已用完', 'info', 20, 'active', 1),
       ('invite_status', 'expired', '已过期', 'info', 30, 'active', 1),
       ('invite_status', 'revoked', '已作废', 'danger', 40, 'active', 1),
       ('delivery_type', 'pickup', '自取', 'primary', 10, 'active', 1),
       ('delivery_type', 'delivery', '配送', 'success', 20, 'active', 1),
       ('notify_type', 'newOrder', '新订单提醒', 'warning', 10, 'active', 1),
       ('notify_type', 'accepted', '订单已接单', 'success', 20, 'active', 1),
       ('notify_type', 'rejected', '订单被拒', 'danger', 30, 'active', 1),
       ('notify_type', 'ready', '可以取餐了', 'success', 40, 'active', 1),
       ('notify_type', 'cancelled', '订单已取消', 'info', 50, 'active', 1),
       ('notify_type', 'cancelRequest', '用户申请取消', 'danger', 15, 'active', 1),
       -- 收款方式：初始三项内置（删不掉，只能停用），商家自建项的 builtin 为 0，可删
       ('pay_method', 'wechat', '微信', 'success', 10, 'active', 1),
       ('pay_method', 'alipay', '支付宝', 'primary', 20, 'active', 1),
       ('pay_method', 'cash', '现金', 'warning', 30, 'active', 1),
       -- 商品分类：种子两项内置（存值不可改、删不掉，只能停用）；商家自建的项可删
       ('combo_category', 'bento', '盒饭', 'primary', 10, 'active', 1),
       ('combo_category', 'homemade', '自制菜', 'warning', 20, 'active', 1)
ON DUPLICATE KEY UPDATE `item_label` = `item_label`;

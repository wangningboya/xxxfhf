-- =============================================================================
-- 群内点餐小程序 · 初始化建表
-- MySQL 8.0 / InnoDB / utf8mb4
--
-- 约定：
--   1. 主键统一 VARCHAR(32)，由 MyBatis-Plus 的 ASSIGN_UUID 生成（32 位无横线 UUID）
--   2. 金额字段一律 INT，单位「分」（1 分 = 0.01 元），禁止 DECIMAL/浮点
--   3. 快照类字段用 JSON 列，订单不依赖实时表
--   4. 表名/列名是 MySQL 关键字的一律带反引号：`user`、`order`、`combo`.`desc`、`spec_group`.`required`
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. 用户 user
--
-- 同一张表装两类账号，靠 user_type 区分身份（不用角色区分）：
--   · customer 群成员：openid 有值，username / password_hash 为 NULL，走微信登录
--   · merchant 商家  ：username / password_hash 有值，openid 为 NULL，走账密登录
--
-- openid 与 username 各建唯一索引。MySQL 唯一索引允许多个 NULL，
-- 因此两类账号在对方那条索引上互不冲突。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`            VARCHAR(32)  NOT NULL COMMENT '主键',
    `user_type`     VARCHAR(16)  NOT NULL DEFAULT 'customer' COMMENT 'customer 群成员 / merchant 商家',
    `openid`        VARCHAR(64)           DEFAULT NULL COMMENT '微信 openid；商家为 NULL',
    `username`      VARCHAR(64)           DEFAULT NULL COMMENT '商家登录账号；群成员为 NULL',
    `password_hash` VARCHAR(100)          DEFAULT NULL COMMENT 'BCrypt 哈希；群成员为 NULL',
    `nick_name`     VARCHAR(64)           DEFAULT NULL COMMENT '微信昵称 / 商家显示名',
    `avatar_url`    VARCHAR(512)          DEFAULT NULL COMMENT '头像地址',
    `group_open_id` VARCHAR(128)          DEFAULT NULL COMMENT '绑定来源群的 openGId',
    `audit_status`  VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected/disabled',
    `reject_reason` VARCHAR(255)          DEFAULT NULL COMMENT '驳回原因',
    `phone`         VARCHAR(20)           DEFAULT NULL COMMENT '手机号，商家填或用户授权',
    `role_ids`      JSON                  DEFAULT NULL COMMENT '角色 ID 数组；决定后台权限',
    `login_fail_count` INT      NOT NULL DEFAULT 0 COMMENT '账密连续失败次数；登录成功清零，到 3 触发锁定',
    `lock_until`    DATETIME              DEFAULT NULL COMMENT '锁定截止时间；到期自动解锁，管理员可提前解除',
    `token_version` INT          NOT NULL DEFAULT 0 COMMENT '令牌版本号；踢人 = +1，与令牌内 ver 不一致即 401',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `approved_at`   DATETIME              DEFAULT NULL COMMENT '审核通过时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_openid` (`openid`),
    UNIQUE KEY `uk_user_username` (`username`),
    KEY `idx_user_audit_created` (`audit_status`, `created_at`),
    KEY `idx_user_type` (`user_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户（群成员 + 商家账号）';

-- -----------------------------------------------------------------------------
-- 2. 区域 region（商家创建；区域下才是用户详细地址）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `region` (
    `id`         VARCHAR(32) NOT NULL COMMENT '主键',
    `name`       VARCHAR(64) NOT NULL COMMENT '区域名称，如「幸福小区」',
    `remark`     VARCHAR(255)         DEFAULT NULL COMMENT '备注，如「东门进，找 3 栋」',
    `sort`       INT         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`     VARCHAR(16) NOT NULL DEFAULT 'enabled' COMMENT 'enabled/disabled',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_region_name` (`name`),
    KEY `idx_region_status_sort` (`status`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='区域';

-- -----------------------------------------------------------------------------
-- 3. 地址 address（两级：region -> detail；一个用户可跨区域持有多地址）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `address` (
    `id`           VARCHAR(32)  NOT NULL COMMENT '主键',
    `user_id`      VARCHAR(32)  NOT NULL COMMENT '归属用户',
    `region_id`    VARCHAR(32)  NOT NULL COMMENT '所属区域，必填',
    `region_name`  VARCHAR(64)           DEFAULT NULL COMMENT '区域名称冗余，便于列表展示',
    `contact_name` VARCHAR(32)           DEFAULT NULL COMMENT '收货人',
    `phone`        VARCHAR(20)  NOT NULL COMMENT '联系电话，必填（见需求 4.8）',
    `detail`       VARCHAR(255) NOT NULL COMMENT '详细地址（区域内门牌）',
    `tag`          VARCHAR(32)           DEFAULT NULL COMMENT '家 / 公司',
    `source`       VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT 'merchant/user',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active 可用 / pending 待确认 / rejected 已驳回',
    `reject_reason` VARCHAR(200)          DEFAULT NULL COMMENT '驳回原因，status=rejected 时有值',
    -- 审计四列（N-27）。pending 队列要按提交时间先来先审，所以 created_at 同时是业务字段。
    -- created_by / updated_by 存操作人 user_id；无登录态的定时任务写 'system'。
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（用户提交时刻）',
    `created_by`   VARCHAR(32)           DEFAULT NULL COMMENT '创建人 user_id，系统写 system',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    `updated_by`   VARCHAR(32)           DEFAULT NULL COMMENT '最后修改人 user_id，系统写 system',
    PRIMARY KEY (`id`),
    KEY `idx_address_user_status` (`user_id`, `status`),
    KEY `idx_address_region` (`region_id`),
    -- 待办队列的默认查询：status='pending' 按提交时间正序。没有这个索引，
    -- 待确认积到几千条时每次进页面都是一次全表扫
    KEY `idx_address_status_created` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='收货地址';

-- -----------------------------------------------------------------------------
-- 4. 菜品 dish（原料层，无价格、不配图——照片只属于盒饭封面）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `dish` (
    `id`          VARCHAR(32)  NOT NULL COMMENT '主键',
    `name`        VARCHAR(64)  NOT NULL COMMENT '菜名',
    `category_id` VARCHAR(32)           DEFAULT NULL COMMENT '菜品分类',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'enabled' COMMENT 'enabled/disabled',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (`id`),
    KEY `idx_dish_status_sort` (`status`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='菜品（原料）';

-- -----------------------------------------------------------------------------
-- 5. 盒饭 combo（售卖层，整份定价）
--    注意：desc 是 MySQL 关键字，必须带反引号
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `combo` (
    `id`             VARCHAR(32)  NOT NULL COMMENT '主键',
    `name`           VARCHAR(64)  NOT NULL COMMENT '盒饭名称，如「A 餐」',
    `cover`          VARCHAR(512)          DEFAULT NULL COMMENT '封面图',
    `desc`           VARCHAR(512)          DEFAULT NULL COMMENT '简介',
    `price`          INT          NOT NULL DEFAULT 0 COMMENT '价格（分）',
    `dishes`         JSON                  DEFAULT NULL COMMENT '组成菜品 [{dishId,name,qty}]',
    `spec_group_ids` JSON                  DEFAULT NULL COMMENT '启用的规格组 ID 数组',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'off' COMMENT 'on/off',
    `category_id`    VARCHAR(32)           DEFAULT NULL COMMENT '预留分类，本版不参与展示分组',
    `sort`           INT          NOT NULL DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (`id`),
    KEY `idx_combo_status_sort` (`status`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='盒饭（售卖单元）';

-- -----------------------------------------------------------------------------
-- 6. 规格组 spec_group（当前只有「主食」；price_delta 是差价，不是份数）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `spec_group` (
    `id`            VARCHAR(32) NOT NULL COMMENT '主键',
    `name`          VARCHAR(64) NOT NULL COMMENT '组名，如「主食」',
    `required`      TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否必选',
    `count_in_prep` TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否计入备菜统计',
    `options`       JSON                 DEFAULT NULL COMMENT '选项 [{optionId,name,priceDelta,sort}]',
    `sort`          INT         NOT NULL DEFAULT 0 COMMENT '组的展示顺序',
    `status`        VARCHAR(16) NOT NULL DEFAULT 'enabled' COMMENT 'enabled/disabled',
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_spec_group_status_sort` (`status`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='规格组';

-- -----------------------------------------------------------------------------
-- 7. 每日菜单 daily_menu（供餐日 + 份数快照；sold_qty 为「下单即占」的已占用份数）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `daily_menu` (
    `id`             VARCHAR(32) NOT NULL COMMENT '主键',
    `serve_date`     DATE        NOT NULL COMMENT '供餐日期',
    `combo_id`       VARCHAR(32) NOT NULL COMMENT '盒饭 ID',
    `combo_snapshot` JSON                 DEFAULT NULL COMMENT '名称/封面/菜品/规格的完整快照',
    `price_snapshot` INT         NOT NULL DEFAULT 0 COMMENT '盒饭价格快照（分）',
    `limit_qty`      INT                  DEFAULT NULL COMMENT '限量总份数，NULL 表示不限',
    `sold_qty`       INT         NOT NULL DEFAULT 0 COMMENT '已占用份数（下单即占）',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/published',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_menu_date_combo` (`serve_date`, `combo_id`),
    KEY `idx_menu_date_status` (`serve_date`, `status`),
    KEY `idx_menu_combo` (`combo_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='每日菜单';

-- -----------------------------------------------------------------------------
-- 8. 订单 `order`
--    items / region_snapshot / address_snapshot / cancel_request 为 JSON 快照，
--    历史订单不受后续改价、改配菜、改区域名影响。
--
--    request_id：下单幂等号，前端每次提交生成一个。唯一索引拦住「双击 / 网络重试」
--    造成的重复下单与重复占份。允许 NULL —— 手工补单、代客下单这类没有前端幂等号的场景，
--    MySQL 唯一索引允许多个 NULL，不会互相冲突。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order` (
    `id`               VARCHAR(32) NOT NULL COMMENT '主键',
    `order_no`         VARCHAR(32) NOT NULL COMMENT '订单号，yyyyMMdd-000001',
    `request_id`       VARCHAR(64)          DEFAULT NULL COMMENT '下单幂等号，前端每次提交生成',
    `user_id`          VARCHAR(32) NOT NULL COMMENT '下单用户',
    `serve_date`       DATE        NOT NULL COMMENT '供餐日期',
    `delivery_type`    VARCHAR(16) NOT NULL COMMENT 'pickup/delivery',
    `region_id`        VARCHAR(32)          DEFAULT NULL COMMENT '配送单区域；自取单为空',
    `region_snapshot`  JSON                 DEFAULT NULL COMMENT '{regionId,regionName}',
    `address_snapshot` JSON                 DEFAULT NULL COMMENT '收货人/电话/详细地址快照',
    `items`            JSON                 DEFAULT NULL COMMENT '明细快照（含规格名与差价）',
    `goods_amount`     INT         NOT NULL DEFAULT 0 COMMENT '商品金额（分）',
    `spec_amount`      INT         NOT NULL DEFAULT 0 COMMENT '规格差价合计（分），当前为 0',
    `delivery_fee`     INT         NOT NULL DEFAULT 0 COMMENT '配送费（分）',
    `total_amount`     INT         NOT NULL DEFAULT 0 COMMENT '合计（分）',
    `status`           VARCHAR(24) NOT NULL COMMENT 'pending_accept/accepted/ready(自取=待取餐，配送=配送中)/completed/cancelled/rejected',
    `cancel_requested` TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '取消申请中，为 1 时禁止接单',
    `cancel_request`   JSON                 DEFAULT NULL COMMENT '取消申请与处理结果',
    `cancel_requested_at` DATETIME          DEFAULT NULL COMMENT '取消申请提交时间，超时自动同意按此列扫描',
    `pay_status`       VARCHAR(16) NOT NULL DEFAULT 'unpaid' COMMENT 'unpaid/claimed/paid/refunding/refunded',
    `pay_method`       VARCHAR(64)          DEFAULT NULL COMMENT '收款方式：字典 pay_method 的 item_value（宽度与 dict_item.item_value 一致）；当前值，下单时为空，用户申报或商家确认收款时写入',
    `pickup_code`      CHAR(4)              DEFAULT NULL COMMENT '4 位取餐码，当日唯一',
    `pickup_slot`      VARCHAR(32)          DEFAULT NULL COMMENT '自取时段，仅自取单有值',
    `stock_released`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '份数是否已被超时释放',
    `stock_released_at` DATETIME           DEFAULT NULL COMMENT '释放时间',
    `reject_reason`    VARCHAR(255)         DEFAULT NULL COMMENT '商家拒单原因，必填且展示给用户',
    `remark`           VARCHAR(255)         DEFAULT NULL COMMENT '备注',
    `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `accepted_at`      DATETIME             DEFAULT NULL COMMENT '接单时间',
    `claimed_at`       DATETIME             DEFAULT NULL COMMENT '用户声称已转账时间',
    `paid_at`          DATETIME             DEFAULT NULL COMMENT '商家确认收款时间',
    `refunded_at`      DATETIME             DEFAULT NULL COMMENT '退款完成时间',
    `refund_operator`  VARCHAR(32)          DEFAULT NULL COMMENT '标记退款的操作人，防重复退',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_order_request` (`request_id`),
    UNIQUE KEY `uk_order_date_pickup` (`serve_date`, `pickup_code`),
    KEY `idx_order_user_created` (`user_id`, `created_at`),
    KEY `idx_order_date_status` (`serve_date`, `status`),
    KEY `idx_order_date_region` (`serve_date`, `region_id`),
    KEY `idx_order_date_pay` (`serve_date`, `pay_status`),
    -- 超时未转账扫描专用（技术文档 4.4）
    KEY `idx_order_release_scan` (`status`, `pay_status`, `stock_released`, `created_at`),
    -- 取消申请超时自动同意扫描专用
    KEY `idx_order_cancel_scan` (`cancel_requested`, `status`, `cancel_requested_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单';

-- -----------------------------------------------------------------------------
-- 9. 邀请码 invite_code（一次性 + 有效期，群卡片走不通时的正式兜底路径）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `invite_code` (
    `id`         VARCHAR(32) NOT NULL COMMENT '主键',
    `code`       VARCHAR(32) NOT NULL COMMENT '邀请码',
    `status`     VARCHAR(16) NOT NULL DEFAULT 'unused' COMMENT 'unused/used/expired',
    `expire_at`  DATETIME    NOT NULL COMMENT '过期时间',
    `used_by`    VARCHAR(32)          DEFAULT NULL COMMENT '使用者 userId',
    `used_at`    DATETIME             DEFAULT NULL COMMENT '使用时间',
    `created_by` VARCHAR(32)          DEFAULT NULL COMMENT '生成人 userId',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invite_code` (`code`),
    KEY `idx_invite_status_expire` (`status`, `expire_at`),
    -- 登录时判「这个用户是否靠邀请码进来的」要按 used_by 反查，必须走索引
    KEY `idx_invite_used_by` (`used_by`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='邀请码';

-- -----------------------------------------------------------------------------
-- 10. 角色 role（内置「商家」角色 builtin = 1，权限不可被改）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `role` (
    `id`          VARCHAR(32) NOT NULL COMMENT '主键',
    `name`        VARCHAR(64) NOT NULL COMMENT '角色名称',
    `permissions` JSON                 DEFAULT NULL COMMENT '权限项数组',
    `builtin`     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否内置角色',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='角色';

-- -----------------------------------------------------------------------------
-- 11. 配置 config（键值型，一项一行）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `config` (
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '配置键',
    `config_value` TEXT COMMENT '配置值（JSON 或纯文本）',
    `remark`       VARCHAR(255)          DEFAULT NULL COMMENT '说明',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='系统配置';

-- -----------------------------------------------------------------------------
-- 12. 字典类型 dict_type
--
-- 为什么要字典：状态中文名、颜色散落在 Java 的 switch 里，改一个说法要发版。
-- 内置类型（builtin=1）**只允许改显示名 / 颜色 / 排序 / 启停，不允许增删项** ——
-- 往 order_status 里加一个库里不存在的值，订单状态机就出现无法处理的数据。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `dict_type` (
    `type_code`  VARCHAR(64)  NOT NULL COMMENT '类型编码，代码里按它取值，如 order_status',
    `type_name`  VARCHAR(64)  NOT NULL COMMENT '类型名称，如「订单状态」',
    `builtin`    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 内置类型（项不可增删），0 自定义',
    `remark`     VARCHAR(255)          DEFAULT NULL COMMENT '说明',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`type_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='字典类型';

-- -----------------------------------------------------------------------------
-- 13. 字典项 dict_item
-- -----------------------------------------------------------------------------
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
-- 14. 操作记录 audit_log（N-27）
--
-- 是谁、什么时候、对哪条记录、做了什么。**追加型表**：只 insert，不 update、不 delete
-- （真要清理按 created_at 归档），因此没有 updated_at / updated_by —— 一行写进去就不会再改，
-- 再挂两列「最后修改人」永远为空，只会误导。
--
-- 与 address 等表上的审计四列分工：
--   行内四列回答「这条记录最后是谁动的」；本表回答「谁在什么时候动了它、动了什么」。
-- 只存 summary 一句话，不存改前改后完整快照：回溯时回业务表看现状，历史值对该项目价值不高。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`          VARCHAR(32)  NOT NULL COMMENT '主键',
    `table_name`  VARCHAR(32)  NOT NULL COMMENT '对象表：address / user（订单域见 order_status_log）',
    `record_id`   VARCHAR(32)  NOT NULL COMMENT '对象主键',
    `action`      VARCHAR(32)  NOT NULL COMMENT '动作码，如 address.approve / user.disable',
    `summary`     VARCHAR(500) NOT NULL COMMENT '一句话说明，人读的，含关键字段前后值',
    `operator_id` VARCHAR(32)  NOT NULL COMMENT '操作人 user_id；定时任务写 system',
    `operator_name` VARCHAR(64)         DEFAULT NULL COMMENT '操作人当时的昵称/账号快照，改名后仍可读',
    `source`      VARCHAR(16)  NOT NULL COMMENT 'admin 网页后台 / miniprogram 小程序 / task 定时任务',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (`id`),
    -- 点某条地址/用户看它的历史
    KEY `idx_audit_record` (`table_name`, `record_id`),
    -- 列表页按时间倒序翻页
    KEY `idx_audit_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='操作记录';

-- -----------------------------------------------------------------------------
-- 15. 订单状态流转记录 order_status_log（N-30）
--
-- 订单域**不复用** audit_log：那张表服务的是「运维追责」，只存一句话摘要；
-- 而这里要的是「商家看懂这一单」—— from_status → to_status 是结构化字段，
-- 前端才能按状态上色、算节点耗时。混在通用表里只能从中文句子里抠状态值。
--
-- **追加型**：只 insert，不改不删（清理按 created_at 归档），所以没有 updated_at / updated_by。
--
-- created_at 用 DATETIME(3) 而不是 DATETIME：接单后立刻点出餐，两条记录常常落在同一秒内，
-- 只到秒时「时间正序」会退化成随机序，时间轴可能把出餐排在接单前面。
-- 存毫秒只用于排序，界面仍显示到秒。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_status_log` (
    `id`            VARCHAR(32)  NOT NULL COMMENT '主键',
    `order_id`      VARCHAR(32)  NOT NULL COMMENT '订单主键',
    `log_type`      VARCHAR(16)  NOT NULL COMMENT 'order 订单状态线 / pay 收款状态线 / event 业务事件',
    `action`        VARCHAR(32)  NOT NULL COMMENT '动作码，如 order.accept / pay.confirm / event.cancel_apply',
    `from_status`   VARCHAR(32)           DEFAULT NULL COMMENT '变更前存值；无前值（如下单）为空',
    `to_status`     VARCHAR(32)           DEFAULT NULL COMMENT '变更后存值；event 类为空',
    `remark`        VARCHAR(200)          DEFAULT NULL COMMENT '补充说明：拒单原因、取消原因、释放原因等',
    -- 方式值只在 pay.claim（用户申报）与 pay.confirm（商家确认）两条上有值，
    -- 两条含义不同靠 action 区分；不单开「方式变更」记录，两行并排即是差异依据。
    `pay_method`    VARCHAR(64)           DEFAULT NULL COMMENT '收款方式快照：字典 pay_method 的 item_value（宽度与 dict_item.item_value 一致）；仅 pay.claim 与 pay.confirm 有值',
    `operator_id`   VARCHAR(32)  NOT NULL COMMENT '操作人 user_id；系统任务写 system',
    `operator_name` VARCHAR(64)           DEFAULT NULL COMMENT '操作人当时的名称快照',
    `source`        VARCHAR(16)  NOT NULL COMMENT 'admin 网页后台 / miniprogram 小程序 / task 定时任务',
    `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间（毫秒仅用于排序）',
    PRIMARY KEY (`id`),
    -- 本期唯一查询形态：某订单的全部记录，按时间正序
    KEY `idx_osl_order` (`order_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单状态流转记录';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 演示数据（可重复执行；只在开发/测试库手动执行，生产库不要跑）
--
-- ⚠️ 本文件放在 mysql/demo/ 而不是 mysql/init/，**不会**被 docker-entrypoint-initdb.d
--    自动执行（init/ 目录挂在 /docker-entrypoint-initdb.d:ro，放那里 = 生产首启自动
--    写入演示数据）。要给某个库灌数据时手动执行：
--      docker exec -i meal-mysql sh -c 'mysql -u<user> -p"$MYSQL_PASSWORD" <库名>' < 本文件
--
-- 覆盖：菜品 dish / 规格组 spec_group / 盒饭 combo / 每日菜单 daily_menu
-- 不覆盖：商家账号（应用启动时 AdminAccountInitializer 自动建 admin）、
--          用户与订单（由小程序真实操作产生）、系统配置（02-data.sql 已有初始值）
--
-- ⚠️ 幂等方式：本脚本**先清空自己拥有的 4 张表再全量插入**。
--    主键改为自增后不再有固定 id 可当幂等键，dish/combo/spec_group 也没有业务唯一键，
--    所以「重跑 = 重置这 4 张表的演示数据」。因为库内没有任何外键约束，清空是安全的；
--    但订单里的菜单快照、daily_menu 的 sold_qty 会被重置 —— 仅限开发环境这么做。
--    region 不在清空范围内：地址通过 region_id 引用它，重跑不能打断已有地址。
--
-- 口径对齐代码与需求文档：
--   · 金额一律「分」：15 元 = 1500
--   · 主键由 AUTO_INCREMENT 生成；需要互相引用的地方用「名称 -> id」变量解析
--   · 主食（白米饭 / 杂粮饭）用规格组表达，不作为菜品出现（需求 4.15 警告：
--     否则备菜表会重复计算）
--   · daily_menu 的日期用 CURDATE() 动态生成：明天 / 后天各一份已发布菜单，
--     用户端首页两个 Tab 都有内容可看；快照 JSON 与 combo 表数据一致
--   · 快照 JSON 里的 name 是「展示文案」，与 dish/region 当前名称可以不同 ——
--     快照的目的就是留住下单当时的样子
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 重置本脚本拥有的表（子表在前）
-- -----------------------------------------------------------------------------
TRUNCATE TABLE `daily_menu`;
TRUNCATE TABLE `combo`;
TRUNCATE TABLE `spec_group`;
TRUNCATE TABLE `dish`;

-- -----------------------------------------------------------------------------
-- 规格组：主食（白米饭 / 杂粮饭，差价均为 0 分；必选；计入备菜）
--   optionId 是 JSON 文档内部标识，不对应任何表的主键，仍然是字符串
-- -----------------------------------------------------------------------------
INSERT INTO `spec_group`
    (`name`, `required`, `count_in_prep`, `options`, `sort`, `status`)
VALUES ('主食', 1, 1,
        JSON_ARRAY(
                JSON_OBJECT('optionId', 'opt_rice',  'name', '白米饭', 'priceDelta', 0, 'sort', 1),
                JSON_OBJECT('optionId', 'opt_grain', 'name', '杂粮饭', 'priceDelta', 0, 'sort', 2)
        ),
        1, 'enabled');

-- -----------------------------------------------------------------------------
-- 菜品（原料层，不定价；不含米饭——米饭走规格组）
-- -----------------------------------------------------------------------------
INSERT INTO `dish` (`name`, `category_id`, `status`, `sort`) VALUES
    ('油豆腐烤肉',     NULL, 'enabled', 1),
    ('萝卜干炒酱肉',   NULL, 'enabled', 2),
    ('红烧小鲳鱼2条',   NULL, 'enabled', 3),
    ('土豆炖牛腩', NULL, 'enabled', 4),
    ('番茄炒蛋',   NULL, 'enabled', 5),
    ('麻婆豆腐',   NULL, 'enabled', 6),
    ('蒜蓉西兰花', NULL, 'enabled', 7),
    ('热卤鸡腿', NULL, 'enabled', 8),
    ('土豆胡萝卜烧牛肉', NULL, 'enabled', 9),
    ('咸肉蒸蛋', NULL, 'enabled', 10),
    ('毛豆子炒肉丝', NULL, 'enabled', 11),
    ('四季豆肉末', NULL, 'enabled', 12),
    ('红烧虾', NULL, 'enabled', 13),
    ('肉末茄子', NULL, 'enabled', 14),
    ('红烧鸡块', NULL, 'enabled', 15),
    ('肉末豆腐', NULL, 'enabled', 16),
    ('绿色蔬菜', NULL, 'enabled', 17);

-- -----------------------------------------------------------------------------
-- 把「名称 -> 自增 id」解析一次，下面拼快照 JSON 时直接引用
-- -----------------------------------------------------------------------------
SET @spec_staple  = (SELECT `id` FROM `spec_group` WHERE `name` = '主食' LIMIT 1);

SET @dish_001 = (SELECT `id` FROM `dish` WHERE `name` = '油豆腐烤肉' LIMIT 1);
SET @dish_002 = (SELECT `id` FROM `dish` WHERE `name` = '萝卜干炒酱肉' LIMIT 1);
SET @dish_003 = (SELECT `id` FROM `dish` WHERE `name` = '红烧小鲳鱼2条' LIMIT 1);
SET @dish_004 = (SELECT `id` FROM `dish` WHERE `name` = '土豆炖牛腩' LIMIT 1);
SET @dish_005 = (SELECT `id` FROM `dish` WHERE `name` = '番茄炒蛋' LIMIT 1);
SET @dish_006 = (SELECT `id` FROM `dish` WHERE `name` = '麻婆豆腐' LIMIT 1);
SET @dish_007 = (SELECT `id` FROM `dish` WHERE `name` = '蒜蓉西兰花' LIMIT 1);
SET @dish_008 = (SELECT `id` FROM `dish` WHERE `name` = '热卤鸡腿' LIMIT 1);

-- -----------------------------------------------------------------------------
-- 盒饭（售卖层；全部启用「主食」规格组；金额单位分）
--   A 餐 15 元 / B 餐 13 元 / C 餐 18 元（例汤 2 份，演示菜品份数>1）/ 全素餐 10 元
-- -----------------------------------------------------------------------------
INSERT INTO `combo`
    (`name`, `cover`, `desc`, `price`, `dishes`, `spec_group_ids`, `status`, `sort`)
VALUES
    ('A 餐', NULL, '经典荤素搭配，红烧肉软糯入味', 1500,
     JSON_ARRAY(
             JSON_OBJECT('dishId', @dish_001, 'name', '红烧肉', 'qty', 1),
             JSON_OBJECT('dishId', @dish_002, 'name', '清炒时蔬', 'qty', 1)
     ),
     JSON_ARRAY(@spec_staple), 'on', 1),

    ('B 餐', NULL, '下饭首选，配例汤一碗', 1300,
     JSON_ARRAY(
             JSON_OBJECT('dishId', @dish_003, 'name', '宫保鸡丁', 'qty', 1),
             JSON_OBJECT('dishId', @dish_002, 'name', '清炒时蔬', 'qty', 1),
             JSON_OBJECT('dishId', @dish_008, 'name', '紫菜蛋花汤', 'qty', 1)
     ),
     JSON_ARRAY(@spec_staple), 'on', 2),

    ('C 餐', NULL, '分量十足，牛腩炖到脱骨，例汤给两碗', 1800,
     JSON_ARRAY(
             JSON_OBJECT('dishId', @dish_004, 'name', '土豆炖牛腩', 'qty', 1),
             JSON_OBJECT('dishId', @dish_007, 'name', '蒜蓉西兰花', 'qty', 1),
             JSON_OBJECT('dishId', @dish_008, 'name', '紫菜蛋花汤', 'qty', 2)
     ),
     JSON_ARRAY(@spec_staple), 'on', 3),

    ('全素餐', NULL, '三素清爽组合，轻负担', 1000,
     JSON_ARRAY(
             JSON_OBJECT('dishId', @dish_005, 'name', '番茄炒蛋', 'qty', 1),
             JSON_OBJECT('dishId', @dish_006, 'name', '麻婆豆腐', 'qty', 1),
             JSON_OBJECT('dishId', @dish_002, 'name', '清炒时蔬', 'qty', 1)
     ),
     JSON_ARRAY(@spec_staple), 'on', 4);

-- -----------------------------------------------------------------------------
-- 区域（配送片区）—— 按 uk_region_name 幂等，不清空：已有地址通过 region_id 引用它
-- -----------------------------------------------------------------------------
INSERT INTO `region` (`name`, `remark`, `sort`, `status`) VALUES
    ('幸福小区', '东门进，找 3 栋集中放货架', 1, 'enabled'),
    ('阳光花园', '南门保安亭自取',           2, 'enabled'),
    ('科技园 A 座', '前台代收，报取餐码',     3, 'enabled')
ON DUPLICATE KEY UPDATE `name` = `name`;

SET @combo_a = (SELECT `id` FROM `combo` WHERE `name` = 'A 餐' LIMIT 1);
SET @combo_b = (SELECT `id` FROM `combo` WHERE `name` = 'B 餐' LIMIT 1);
SET @combo_c = (SELECT `id` FROM `combo` WHERE `name` = 'C 餐' LIMIT 1);
SET @combo_v = (SELECT `id` FROM `combo` WHERE `name` = '全素餐' LIMIT 1);

-- -----------------------------------------------------------------------------
-- 每日菜单（动态日期：明天 / 后天，已发布）
--   快照 JSON 与上面 combo 的数据完全一致，价格快照 = 盒饭现价
--   明天：A 餐 30 份 / B 餐 20 份；后天：C 餐 15 份 / 全素餐 10 份（不限量演示 NULL）
-- -----------------------------------------------------------------------------
INSERT INTO `daily_menu`
    (`serve_date`, `combo_id`, `combo_snapshot`, `price_snapshot`, `limit_qty`, `sold_qty`, `status`)
VALUES
    (CURDATE() + INTERVAL 1 DAY, @combo_a,
     JSON_OBJECT(
             'name', 'A 餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', @dish_001, 'name', '红烧肉', 'qty', 1),
                     JSON_OBJECT('dishId', @dish_002, 'name', '清炒时蔬', 'qty', 1)
             ),
             'specGroups', JSON_ARRAY(
                     JSON_OBJECT('name', '主食', 'required', TRUE, 'countInPrep', TRUE,
                                 'options', JSON_ARRAY(
                                         JSON_OBJECT('optionId', 'opt_rice', 'name', '白米饭', 'priceDelta', 0, 'sort', 1),
                                         JSON_OBJECT('optionId', 'opt_grain', 'name', '杂粮饭', 'priceDelta', 0, 'sort', 2)
                                 ))
             )
     ),
     1500, 30, 0, 'published'),

    (CURDATE() + INTERVAL 1 DAY, @combo_b,
     JSON_OBJECT(
             'name', 'B 餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', @dish_003, 'name', '宫保鸡丁', 'qty', 1),
                     JSON_OBJECT('dishId', @dish_002, 'name', '清炒时蔬', 'qty', 1),
                     JSON_OBJECT('dishId', @dish_008, 'name', '紫菜蛋花汤', 'qty', 1)
             ),
             'specGroups', JSON_ARRAY(
                     JSON_OBJECT('name', '主食', 'required', TRUE, 'countInPrep', TRUE,
                                 'options', JSON_ARRAY(
                                         JSON_OBJECT('optionId', 'opt_rice', 'name', '白米饭', 'priceDelta', 0, 'sort', 1),
                                         JSON_OBJECT('optionId', 'opt_grain', 'name', '杂粮饭', 'priceDelta', 0, 'sort', 2)
                                 ))
             )
     ),
     1300, 20, 0, 'published'),

    (CURDATE() + INTERVAL 2 DAY, @combo_c,
     JSON_OBJECT(
             'name', 'C 餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', @dish_004, 'name', '土豆炖牛腩', 'qty', 1),
                     JSON_OBJECT('dishId', @dish_007, 'name', '蒜蓉西兰花', 'qty', 1),
                     JSON_OBJECT('dishId', @dish_008, 'name', '紫菜蛋花汤', 'qty', 2)
             ),
             'specGroups', JSON_ARRAY(
                     JSON_OBJECT('name', '主食', 'required', TRUE, 'countInPrep', TRUE,
                                 'options', JSON_ARRAY(
                                         JSON_OBJECT('optionId', 'opt_rice', 'name', '白米饭', 'priceDelta', 0, 'sort', 1),
                                         JSON_OBJECT('optionId', 'opt_grain', 'name', '杂粮饭', 'priceDelta', 0, 'sort', 2)
                                 ))
             )
     ),
     1800, 15, 0, 'published'),

    (CURDATE() + INTERVAL 2 DAY, @combo_v,
     JSON_OBJECT(
             'name', '全素餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', @dish_005, 'name', '番茄炒蛋', 'qty', 1),
                     JSON_OBJECT('dishId', @dish_006, 'name', '麻婆豆腐', 'qty', 1),
                     JSON_OBJECT('dishId', @dish_002, 'name', '清炒时蔬', 'qty', 1)
             ),
             'specGroups', JSON_ARRAY(
                     JSON_OBJECT('name', '主食', 'required', TRUE, 'countInPrep', TRUE,
                                 'options', JSON_ARRAY(
                                         JSON_OBJECT('optionId', 'opt_rice', 'name', '白米饭', 'priceDelta', 0, 'sort', 1),
                                         JSON_OBJECT('optionId', 'opt_grain', 'name', '杂粮饭', 'priceDelta', 0, 'sort', 2)
                                 ))
             )
     ),
     1000, NULL, 0, 'published');

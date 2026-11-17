-- =============================================================================
-- 演示数据（幂等，可重复执行；只在开发/测试库手动执行，生产库不要跑）
--
-- ⚠️ 本文件放在 mysql/demo/ 而不是 mysql/init/，**不会**被 docker-entrypoint-initdb.d
--    自动执行（init/ 目录挂在 /docker-entrypoint-initdb.d:ro，放那里 = 生产首启自动
--    写入演示数据）。要给某个库灌数据时手动执行：
--      docker exec -i meal-mysql sh -c 'mysql -u<user> -p"$MYSQL_PASSWORD" <库名>' < 本文件
--
-- 覆盖：菜品 dish / 规格组 spec_group / 盒饭 combo / 区域 region / 每日菜单 daily_menu
-- 不覆盖：商家账号（应用启动时 AdminAccountInitializer 自动建 admin）、
--          用户与订单（由小程序真实操作产生）、系统配置（02-data.sql 已有初始值）
--
-- 口径对齐代码与需求文档：
--   · 金额一律「分」：15 元 = 1500
--   · 主键 VARCHAR(32)，这里用可读固定 ID（dish_001 等），MyBatis-Plus 不会撞
--   · 主食（白米饭 / 杂粮饭）用规格组表达，不作为菜品出现（需求 4.15 警告：
--     否则备菜表会重复计算）
--   · daily_menu 的日期用 CURDATE() 动态生成：明天 / 后天各一份已发布菜单，
--     用户端首页两个 Tab 都有内容可看；快照 JSON 与 combo 表数据一致
--   · 幂等方式：固定主键 + INSERT IGNORE；daily_menu 按 (serve_date, combo_id)
--     唯一键 IGNORE，同一天重复执行不会重复插入
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 规格组：主食（白米饭 / 杂粮饭，差价均为 0 分；必选；计入备菜）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `spec_group`
    (`id`, `name`, `required`, `count_in_prep`, `options`, `sort`, `status`)
VALUES ('spec_staple', '主食', 1, 1,
        JSON_ARRAY(
                JSON_OBJECT('optionId', 'opt_rice',  'name', '白米饭', 'priceDelta', 0, 'sort', 1),
                JSON_OBJECT('optionId', 'opt_grain', 'name', '杂粮饭', 'priceDelta', 0, 'sort', 2)
        ),
        1, 'enabled');

-- -----------------------------------------------------------------------------
-- 菜品（原料层，不定价；不含米饭——米饭走规格组）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `dish` (`id`, `name`, `image`, `category_id`, `status`, `sort`) VALUES
    ('dish_001', '红烧肉',     NULL, NULL, 'enabled', 1),
    ('dish_002', '清炒时蔬',   NULL, NULL, 'enabled', 2),
    ('dish_003', '宫保鸡丁',   NULL, NULL, 'enabled', 3),
    ('dish_004', '土豆炖牛腩', NULL, NULL, 'enabled', 4),
    ('dish_005', '番茄炒蛋',   NULL, NULL, 'enabled', 5),
    ('dish_006', '麻婆豆腐',   NULL, NULL, 'enabled', 6),
    ('dish_007', '蒜蓉西兰花', NULL, NULL, 'enabled', 7),
    ('dish_008', '紫菜蛋花汤', NULL, NULL, 'enabled', 8);

-- -----------------------------------------------------------------------------
-- 盒饭（售卖层；全部启用「主食」规格组；金额单位分）
--   A 餐 15 元 / B 餐 13 元 / C 餐 18 元（例汤 2 份，演示菜品份数>1）/ 全素餐 10 元
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `combo`
    (`id`, `name`, `cover`, `desc`, `price`, `dishes`, `spec_group_ids`, `status`, `sort`)
VALUES
    ('combo_001', 'A 餐', NULL, '经典荤素搭配，红烧肉软糯入味', 1500,
     JSON_ARRAY(
             JSON_OBJECT('dishId', 'dish_001', 'name', '红烧肉', 'qty', 1),
             JSON_OBJECT('dishId', 'dish_002', 'name', '清炒时蔬', 'qty', 1)
     ),
     JSON_ARRAY('spec_staple'), 'on', 1),

    ('combo_002', 'B 餐', NULL, '下饭首选，配例汤一碗', 1300,
     JSON_ARRAY(
             JSON_OBJECT('dishId', 'dish_003', 'name', '宫保鸡丁', 'qty', 1),
             JSON_OBJECT('dishId', 'dish_002', 'name', '清炒时蔬', 'qty', 1),
             JSON_OBJECT('dishId', 'dish_008', 'name', '紫菜蛋花汤', 'qty', 1)
     ),
     JSON_ARRAY('spec_staple'), 'on', 2),

    ('combo_003', 'C 餐', NULL, '分量十足，牛腩炖到脱骨，例汤给两碗', 1800,
     JSON_ARRAY(
             JSON_OBJECT('dishId', 'dish_004', 'name', '土豆炖牛腩', 'qty', 1),
             JSON_OBJECT('dishId', 'dish_007', 'name', '蒜蓉西兰花', 'qty', 1),
             JSON_OBJECT('dishId', 'dish_008', 'name', '紫菜蛋花汤', 'qty', 2)
     ),
     JSON_ARRAY('spec_staple'), 'on', 3),

    ('combo_004', '全素餐', NULL, '三素清爽组合，轻负担', 1000,
     JSON_ARRAY(
             JSON_OBJECT('dishId', 'dish_005', 'name', '番茄炒蛋', 'qty', 1),
             JSON_OBJECT('dishId', 'dish_006', 'name', '麻婆豆腐', 'qty', 1),
             JSON_OBJECT('dishId', 'dish_002', 'name', '清炒时蔬', 'qty', 1)
     ),
     JSON_ARRAY('spec_staple'), 'on', 4);

-- -----------------------------------------------------------------------------
-- 区域（配送片区）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `region` (`id`, `name`, `remark`, `sort`, `status`) VALUES
    ('region_001', '幸福小区', '东门进，找 3 栋集中放货架', 1, 'enabled'),
    ('region_002', '阳光花园', '南门保安亭自取',           2, 'enabled'),
    ('region_003', '科技园 A 座', '前台代收，报取餐码',     3, 'enabled');

-- -----------------------------------------------------------------------------
-- 每日菜单（动态日期：明天 / 后天，已发布）
--   快照 JSON 与上面 combo 的数据完全一致，价格快照 = 盒饭现价
--   明天：A 餐 30 份 / B 餐 20 份；后天：C 餐 15 份 / 全素餐 10 份（不限量演示 NULL）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `daily_menu`
    (`id`, `serve_date`, `combo_id`, `combo_snapshot`, `price_snapshot`, `limit_qty`, `sold_qty`, `status`)
VALUES
    (REPLACE(UUID(), '-', ''), CURDATE() + INTERVAL 1 DAY, 'combo_001',
     JSON_OBJECT(
             'name', 'A 餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', 'dish_001', 'name', '红烧肉', 'qty', 1),
                     JSON_OBJECT('dishId', 'dish_002', 'name', '清炒时蔬', 'qty', 1)
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

    (REPLACE(UUID(), '-', ''), CURDATE() + INTERVAL 1 DAY, 'combo_002',
     JSON_OBJECT(
             'name', 'B 餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', 'dish_003', 'name', '宫保鸡丁', 'qty', 1),
                     JSON_OBJECT('dishId', 'dish_002', 'name', '清炒时蔬', 'qty', 1),
                     JSON_OBJECT('dishId', 'dish_008', 'name', '紫菜蛋花汤', 'qty', 1)
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

    (REPLACE(UUID(), '-', ''), CURDATE() + INTERVAL 2 DAY, 'combo_003',
     JSON_OBJECT(
             'name', 'C 餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', 'dish_004', 'name', '土豆炖牛腩', 'qty', 1),
                     JSON_OBJECT('dishId', 'dish_007', 'name', '蒜蓉西兰花', 'qty', 1),
                     JSON_OBJECT('dishId', 'dish_008', 'name', '紫菜蛋花汤', 'qty', 2)
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

    (REPLACE(UUID(), '-', ''), CURDATE() + INTERVAL 2 DAY, 'combo_004',
     JSON_OBJECT(
             'name', '全素餐', 'cover', NULL,
             'dishes', JSON_ARRAY(
                     JSON_OBJECT('dishId', 'dish_005', 'name', '番茄炒蛋', 'qty', 1),
                     JSON_OBJECT('dishId', 'dish_006', 'name', '麻婆豆腐', 'qty', 1),
                     JSON_OBJECT('dishId', 'dish_002', 'name', '清炒时蔬', 'qty', 1)
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

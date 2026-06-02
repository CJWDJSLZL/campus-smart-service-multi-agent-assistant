-- =============================================
-- 校园智能服务多 Agent 助手系统数据库结构
-- 创建时间: 2026-06-02
-- 描述: 保留 users/products/orders/feedback 表名以兼容现有 Mapper，
--       业务语义改造为用户、校园服务事项、事务办理记录、反馈投诉。
-- =============================================

USE `multi-agent-demo`;

-- =============================================
-- 1. 用户表 (users)
-- =============================================
DROP TABLE IF EXISTS `feedback`;
DROP TABLE IF EXISTS `orders`;
DROP TABLE IF EXISTS `products`;
DROP TABLE IF EXISTS `users`;

CREATE TABLE `users` (
    `id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校园用户表';

-- =============================================
-- 2. 校园服务事项表 (products)
-- =============================================
CREATE TABLE `products` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '服务事项ID',
    `name` VARCHAR(100) NOT NULL COMMENT '服务事项名称',
    `description` TEXT COMMENT '服务事项说明',
    `price` DECIMAL(10,2) NOT NULL COMMENT '费用/占位金额',
    `stock` INT(11) DEFAULT 0 COMMENT '剩余名额/可办理容量',
    `shelf_time` INT(11) DEFAULT 30 COMMENT '有效期或开放时长（分钟）',
    `preparation_time` INT(11) DEFAULT 5 COMMENT '预计办理时间（分钟）',
    `is_seasonal` TINYINT(1) DEFAULT 0 COMMENT '是否阶段性开放：0-否，1-是',
    `season_start` DATE DEFAULT NULL COMMENT '开放开始日期',
    `season_end` DATE DEFAULT NULL COMMENT '开放结束日期',
    `is_regional` TINYINT(1) DEFAULT 0 COMMENT '是否限定校区/地点：0-否，1-是',
    `available_regions` JSON DEFAULT NULL COMMENT '可用校区/地点列表',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态：0-不可用，1-可用',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status` (`status`),
    KEY `idx_is_seasonal` (`is_seasonal`),
    KEY `idx_is_regional` (`is_regional`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校园服务事项表';

-- =============================================
-- 3. 校园事务办理记录表 (orders)
-- =============================================
CREATE TABLE `orders` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `order_id` VARCHAR(50) NOT NULL COMMENT '办理/预约记录编号',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `product_id` BIGINT(20) NOT NULL COMMENT '服务事项ID',
    `product_name` VARCHAR(100) NOT NULL COMMENT '服务事项名称',
    `sweetness` TINYINT(1) NOT NULL COMMENT '办理方式：1-线上，2-线下，3-自助，4-窗口，5-加急',
    `ice_level` TINYINT(1) NOT NULL COMMENT '优先级/时间偏好：1-普通，2-优先，3-加急，4-上午，5-下午/晚上',
    `quantity` INT(11) NOT NULL DEFAULT 1 COMMENT '数量/预约名额',
    `unit_price` DECIMAL(10,2) NOT NULL COMMENT '单项费用/占位金额',
    `total_price` DECIMAL(10,2) NOT NULL COMMENT '总费用/占位金额',
    `remark` TEXT DEFAULT NULL COMMENT '办理备注',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '申请/预约时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_orders_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校园事务办理记录表';

-- =============================================
-- 4. 校园反馈投诉表 (feedback)
-- =============================================
CREATE TABLE `feedback` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
    `order_id` VARCHAR(50) DEFAULT NULL COMMENT '关联办理/预约记录编号',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `feedback_type` TINYINT(1) NOT NULL COMMENT '反馈类型：1-服务事项反馈，2-校园服务反馈，3-投诉，4-建议',
    `rating` TINYINT(1) DEFAULT NULL COMMENT '评分：1-5星',
    `content` TEXT NOT NULL COMMENT '反馈内容',
    `solution` TEXT DEFAULT NULL COMMENT '处理方案/回复说明',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_feedback_type` (`feedback_type`),
    KEY `idx_created_at` (`created_at`),
    CONSTRAINT `fk_feedback_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校园反馈投诉表';

-- =============================================
-- 初始化用户数据
-- =============================================
INSERT INTO `users` (`id`, `username`, `phone`, `email`, `nickname`, `status`) VALUES
(10001, 'student_zhang', '13800000001', 'zhangsan@campus.edu.cn', '张三', 1),
(10002, 'student_li', '13800000002', 'lisi@campus.edu.cn', '李四', 1),
(10003, 'student_wang', '13800000003', 'wangwu@campus.edu.cn', '王五', 1),
(20001, 'teacher_chen', '13800000004', 'chen@campus.edu.cn', '陈老师', 1),
(30001, 'admin_service', '13800000005', 'service@campus.edu.cn', '校园服务中心', 1);

-- =============================================
-- 初始化校园服务事项数据
-- =============================================
INSERT INTO `products` (`name`, `description`, `price`, `stock`, `shelf_time`, `preparation_time`, `is_seasonal`, `season_start`, `season_end`, `is_regional`, `available_regions`, `status`) VALUES
('图书馆研讨间预约', '为学习小组、课程讨论和项目答辩准备提供图书馆研讨间预约服务。', 0.00, 30, 120, 10, 0, NULL, NULL, 1, JSON_ARRAY('主校区图书馆', '东校区图书馆'), 1),
('心理咨询预约', '面向学生提供心理咨询初访预约，适用于学习压力、人际关系、情绪困扰等问题。', 0.00, 20, 60, 15, 0, NULL, NULL, 1, JSON_ARRAY('学生发展中心'), 1),
('在读证明办理', '为实习、签证、竞赛报名和资格审核等场景开具在读证明。', 0.00, 200, 1440, 30, 0, NULL, NULL, 0, NULL, 1),
('校园卡补办', '处理校园卡丢失、损坏、磁条异常等补办需求。', 20.00, 100, 1440, 20, 0, NULL, NULL, 1, JSON_ARRAY('校园卡中心', '自助服务机'), 1),
('体育馆预约', '支持篮球场、羽毛球场、乒乓球室等体育场馆预约。', 0.00, 40, 120, 10, 0, NULL, NULL, 1, JSON_ARRAY('体育馆', '风雨操场'), 1),
('宿舍报修', '受理宿舍水电、门锁、空调、网络、家具等设施报修。', 0.00, 300, 1440, 20, 0, NULL, NULL, 1, JSON_ARRAY('学生公寓区'), 1),
('奖学金材料预审', '为奖学金申请人提供材料完整性预审和流程提醒。', 0.00, 80, 1440, 25, 1, '2026-09-01', '2026-10-31', 0, NULL, 1),
('社团活动场地申请', '为学生社团提供教室、报告厅、室外场地申请登记。', 0.00, 50, 240, 20, 0, NULL, NULL, 1, JSON_ARRAY('教学楼', '学生活动中心'), 1);

-- =============================================
-- 初始化办理记录数据
-- =============================================
INSERT INTO `orders` (`order_id`, `user_id`, `product_id`, `product_name`, `sweetness`, `ice_level`, `quantity`, `unit_price`, `total_price`, `remark`) VALUES
('CAMPUS_20260601001', 10001, 1, '图书馆研讨间预约', 1, 5, 1, 0.00, 0.00, '预约明天下午，4人小组讨论使用'),
('CAMPUS_20260601002', 10002, 3, '在读证明办理', 1, 1, 1, 0.00, 0.00, '用于暑期实习报名，申请电子版'),
('CAMPUS_20260601003', 10003, 6, '宿舍报修', 1, 2, 1, 0.00, 0.00, '空调制冷异常，希望下午维修'),
('CAMPUS_20260601004', 20001, 8, '社团活动场地申请', 2, 4, 1, 0.00, 0.00, '讲座活动，预计80人'),
('CAMPUS_20260601005', 10001, 7, '奖学金材料预审', 1, 1, 1, 0.00, 0.00, '希望按清单说明缺少哪些材料');

-- =============================================
-- 初始化反馈投诉数据
-- =============================================
INSERT INTO `feedback` (`order_id`, `user_id`, `feedback_type`, `rating`, `content`, `solution`) VALUES
('CAMPUS_20260601001', 10001, 2, 5, '图书馆研讨间预约流程很清晰，希望能增加设备状态提示。', '已记录建议，后续在预约说明中补充设备信息。'),
('CAMPUS_20260601003', 10003, 3, 2, '宿舍空调报修后等待时间较长，希望能加快处理。', '已转交后勤维修组，并提醒优先处理影响生活的问题。'),
('CAMPUS_20260601002', 10002, 1, 4, '在读证明线上办理方便，但希望能提示审核预计时间。', '已建议教务服务页面增加预计审核时长说明。'),
(NULL, 10001, 4, NULL, '希望图书馆周末延长开放时间。', '已作为服务建议记录，待图书馆管理部门评估。'),
(NULL, 20001, 2, 4, '社团活动场地申请系统整体可用，但希望支持附件上传。', '已记录功能优化建议。');

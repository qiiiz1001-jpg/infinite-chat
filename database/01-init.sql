-- 来源：千言 IM 项目代码.md / 初始化数据库。目标：MySQL 8.0+。
-- 本脚本不删除数据库或表，仅适用于初次建表；不会修正已有表结构。
-- 修正了原文 Markdown 星号及用户 ID 的自增注释，其余字段和索引保持本阶段定义。

-- 创建数据库；已有数据库不会被清空
CREATE DATABASE IF NOT EXISTS Qingliao CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- 使用 Qingliao 数据库
USE Qingliao;


-- 用户表
CREATE TABLE IF NOT EXISTS `user`
(
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID（应用侧生成）',
    `phone`        CHAR(11)              DEFAULT NULL COMMENT '用户手机号',
    `email`        VARCHAR(128) NOT NULL COMMENT '用户邮箱',
    `password`     VARCHAR(256) NOT NULL COMMENT '用户密码',
    `nickname`     VARCHAR(128) NOT NULL COMMENT '用户昵称',
    `avatar`       VARCHAR(512)          DEFAULT NULL COMMENT '用户头像url',
    `gender`       TINYINT(1)   NOT NULL DEFAULT 2 COMMENT '性别 0 女 1 男 2 未知',
    `description`  TEXT                  DEFAULT NULL COMMENT '个性签名',
    `state`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '状态 0 正常 1 封禁 2 注销',
    `role`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '角色类型 0 普通用户 1 管理员 2 超级管理员',
    `created_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_delete`    TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记（0未删 1已删）',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `idx_phone` (`phone`),
    UNIQUE KEY `idx_email` (`email`),
    KEY `idx_state` (`state`),
    KEY `idx_role` (`role`),
    KEY `idx_create_time` (`created_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表'
  ROW_FORMAT = Dynamic;


-- 用户余额表
CREATE TABLE IF NOT EXISTS `user_balance`
(
    `user_id`      BIGINT         NOT NULL COMMENT '用户 ID',
    `balance` bigint NOT NULL DEFAULT 0 COMMENT '余额（单位：分）',
    `created_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户余额表'
  ROW_FORMAT = Dynamic;


-- 余额变动记录表
CREATE TABLE IF NOT EXISTS `balance_log`
(
    `balance_log_id` BIGINT         NOT NULL COMMENT '记录 ID',
    `user_id`        BIGINT         NOT NULL COMMENT '用户 ID',
    `amount` bigint NOT NULL COMMENT '变动金额（单位：分），正数为增加，负数为减少',
    `type`           TINYINT        NOT NULL COMMENT '变动类型：0 发送红包，1 领取红包，2 红包退回',
    `related_id`     BIGINT         NULL     DEFAULT NULL COMMENT '关联 ID，如红包 ID',
    `created_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`balance_log_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '余额变动记录表'
  ROW_FORMAT = Dynamic;


-- 会话表
CREATE TABLE IF NOT EXISTS `session`
(
    `session_id`   BIGINT       NOT NULL COMMENT '会话 ID',
    `name`         VARCHAR(255)     DEFAULT NULL COMMENT '名称',
    `type`         TINYINT      NOT NULL COMMENT '类别：0 单聊，1 群聊',
    `status`       TINYINT      NOT NULL COMMENT '状态：0 正常，1 删除',
    `created_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `avatar` VARCHAR(512) DEFAULT '/assets/group-avatar.png' COMMENT '会话头像',
    PRIMARY KEY (`session_id`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '会话表'
  ROW_FORMAT = Dynamic;


-- 用户会话关系表：复合主键约束成员关系，session_id 索引用于反查成员
CREATE TABLE IF NOT EXISTS `user_session`
(
    `user_id`      BIGINT   NOT NULL COMMENT '用户 id',
    `session_id`   BIGINT   NOT NULL COMMENT '会话 id',
    `role`         TINYINT  NOT NULL COMMENT '角色：0 群主，1 管理员，2 普通用户',
    `status`       TINYINT  NOT NULL COMMENT '状态：0 正常，1 删除',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`, `session_id`),
    INDEX `idx_session_id` (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户会话关系表'
  ROW_FORMAT = Dynamic;


-- 消息表：消息归属会话，后续通过成员关系确定接收者
CREATE TABLE IF NOT EXISTS `message`
(
    `message_id`   BIGINT   NOT NULL COMMENT '消息 id',
    `sender_id`    BIGINT   NOT NULL COMMENT '发送者 id',
    `session_id`   BIGINT   NOT NULL COMMENT '会话 id',
    `type`         TINYINT  NOT NULL COMMENT '消息类型: 0 文本消息，1 图片消息，3 红包，4 表情包',
    `content`      text     NOT NULL COMMENT '消息内容',
    `reply_id`     BIGINT            DEFAULT NULL COMMENT '消息引用 id',
    `session_type` TINYINT  NOT NULL COMMENT '会话类型: 0 单聊，1 群聊',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`message_id`) USING BTREE,
    INDEX `idx_session_time` (`session_id`, `created_time`),
    INDEX `idx_sender_time` (`sender_id`, `created_time`),
    INDEX `idx_reply_id` (`reply_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息表'
  ROW_FORMAT = Dynamic;


-- 好友关系表
CREATE TABLE IF NOT EXISTS `friend`
(
    `user_id`      BIGINT   NOT NULL COMMENT '用户 ID',
    `friend_id`    BIGINT   NOT NULL COMMENT '好友 ID',
    `status`       TINYINT  NOT NULL DEFAULT 0 COMMENT '好友状态：0好友，1拉黑，2删除',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`, `friend_id`),
    INDEX `idx_friend_id` (`friend_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '好友关系表'
  ROW_FORMAT = Dynamic;


-- 好友申请表
CREATE TABLE IF NOT EXISTS `apply_friend` (
                                `apply_friend_id` bigint NOT NULL COMMENT '申请 ID',
                                `sender_id` bigint NOT NULL COMMENT '发送者用户ID',
                                `receiver_id` bigint NOT NULL COMMENT '接收者用户ID',
                                `message` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '请求添加好友' COMMENT '申请信息',
                                `status` tinyint NOT NULL DEFAULT '0' COMMENT '申请状态：0未读，1通过，2拒绝，3已读，4过期',
                                `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                PRIMARY KEY (`apply_friend_id`),
                                UNIQUE KEY `uk_sender_receiver` (`sender_id`,`receiver_id`),
                                KEY `idx_sender_status` (`sender_id`,`status`),
                                KEY `idx_receiver_status` (`receiver_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='好友申请表';


-- 红包表
CREATE TABLE IF NOT EXISTS `red_packet`
(
    `red_packet_id`           BIGINT         NOT NULL COMMENT '红包 ID',
    `sender_id`               BIGINT         NOT NULL COMMENT '发送者用户 ID',
    `session_id`              BIGINT         NOT NULL COMMENT '会话 ID（单聊或群聊）',
    `session_type` TINYINT DEFAULT NULL COMMENT '会话类型：0 单聊，1 群聊',
    `red_packet_wrapper_text` VARCHAR(50)    NOT NULL DEFAULT '恭喜发财，大吉大利' COMMENT '红包封面文案',
    `red_packet_type`         TINYINT        NOT NULL COMMENT '红包类型：0 普通红包，1 拼手气红包',
    `total_amount` bigint NOT NULL COMMENT '红包总金额(单位：分)',
    `total_count`             INT            NOT NULL COMMENT '红包总个数',
    `status`                  TINYINT        NOT NULL DEFAULT 0 COMMENT '状态：0 未领取完，1 已领取完，2 已过期',
    `created_time`            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time`            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`red_packet_id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_sender_id` (`sender_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '红包主表'
  ROW_FORMAT = Dynamic;


-- 红包领取记录表；当前按原文保留普通索引，领取幂等约束在红包阶段核对
CREATE TABLE IF NOT EXISTS `red_packet_receive`
(
    `red_packet_receive_id` BIGINT         NOT NULL COMMENT '记录 ID',
    `red_packet_id`         BIGINT         NOT NULL COMMENT '红包 ID',
    `receiver_id`           BIGINT         NOT NULL COMMENT '领取者用户 ID',
    `amount` bigint NOT NULL COMMENT '领取金额（单位：分）',
    `received_at`           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    `created_time`          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time`          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`red_packet_receive_id`),
    INDEX `idx_red_packet_id` (`red_packet_id`),
    INDEX `idx_receiver_id` (`receiver_id`),
    INDEX `idx_red_packet_receiver` (`red_packet_id`, `receiver_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '红包领取记录表'
  ROW_FORMAT = Dynamic;

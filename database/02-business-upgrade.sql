-- 在 01-init.sql 后执行。本脚本不删除业务数据；已有重复领取记录时，唯一索引创建会报错，应先人工核对。
USE Qingliao;

-- 第 12、13 章相对初始建表新增的字段；同时支持重复执行及已有数据。
SET @session_avatar_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='session' AND column_name='avatar'),
    'SELECT 1',
    'ALTER TABLE `session` ADD COLUMN avatar VARCHAR(512) DEFAULT ''/assets/group-avatar.png'' COMMENT ''会话头像'''
);
PREPARE session_avatar_stmt FROM @session_avatar_ddl;
EXECUTE session_avatar_stmt;
DEALLOCATE PREPARE session_avatar_stmt;

SET @packet_session_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='red_packet' AND column_name='session_type'),
    'SELECT 1',
    'ALTER TABLE red_packet ADD COLUMN session_type TINYINT NULL DEFAULT NULL COMMENT ''会话类型：0 单聊，1 群聊'' AFTER session_id'
);
PREPARE packet_session_stmt FROM @packet_session_ddl;
EXECUTE packet_session_stmt;
DEALLOCATE PREPARE packet_session_stmt;
-- 只补原来缺失的值，不改已有头像和明确会话类型；孤立旧记录保留 NULL 待核对。
UPDATE `session` SET avatar='/assets/group-avatar.png' WHERE avatar IS NULL;
UPDATE red_packet p JOIN `session` s ON s.session_id=p.session_id
    SET p.session_type=s.type WHERE p.session_type IS NULL AND s.type IN (0,1);


SET @receipt_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='red_packet_receive' AND index_name='uk_packet_receiver'),
    'SELECT 1',
    'ALTER TABLE red_packet_receive ADD UNIQUE KEY uk_packet_receiver(red_packet_id,receiver_id)'
);
PREPARE receipt_stmt FROM @receipt_ddl;
EXECUTE receipt_stmt;
DEALLOCATE PREPARE receipt_stmt;

-- 同毫秒分页配合 message_id 游标使用，避免只按秒记录或只按时间过滤漏消息。
ALTER TABLE message MODIFY created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE message MODIFY updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3);

CREATE TABLE IF NOT EXISTS system_notification (
    message_id VARCHAR(128) PRIMARY KEY,
    receiver_id BIGINT NOT NULL,
    type INT NOT NULL,
    payload JSON NOT NULL,
    read_flag TINYINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_notification_receiver_time(receiver_id,created_time,message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
    user_id BIGINT PRIMARY KEY,phone VARCHAR(11),email VARCHAR(128) UNIQUE,password VARCHAR(256),
    nickname VARCHAR(128),avatar VARCHAR(512),gender INT DEFAULT 2,description TEXT,state INT DEFAULT 0,
    role INT DEFAULT 0,created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,is_delete INT DEFAULT 0
);
CREATE TABLE IF NOT EXISTS `session` (
    session_id BIGINT PRIMARY KEY,name VARCHAR(255),type INT,status INT,avatar VARCHAR(512),
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS user_session (
    user_id BIGINT,session_id BIGINT,role INT,status INT,created_time TIMESTAMP,updated_time TIMESTAMP,
    PRIMARY KEY(user_id,session_id)
);
CREATE TABLE IF NOT EXISTS friend (
    user_id BIGINT,friend_id BIGINT,status INT,created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_id,friend_id)
);
CREATE TABLE IF NOT EXISTS apply_friend (
    apply_friend_id BIGINT PRIMARY KEY,sender_id BIGINT,receiver_id BIGINT,message VARCHAR(255),status INT,
    created_time TIMESTAMP,updated_time TIMESTAMP,UNIQUE(sender_id,receiver_id)
);

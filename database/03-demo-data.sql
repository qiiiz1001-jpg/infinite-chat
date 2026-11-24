-- 仅用于本机演示：两个测试账号，密码均为 123456；测试余额不是实际资金。
SET NAMES utf8mb4;
USE Qingliao;
INSERT INTO `user`(user_id,email,password,nickname,state) VALUES
(1001,'demo-a@example.com','f248a27eba558ecb1d611b8d7730bff2','演示用户 A',0),
(2002,'demo-b@example.com','f248a27eba558ecb1d611b8d7730bff2','演示用户 B',0),
(111111111,'ai@example.invalid','2bae18b4ce27dd7f159ed9c90e01b8ef','轻聊 AI',1)
ON DUPLICATE KEY UPDATE user_id=user_id;
INSERT INTO user_balance(user_id,balance) VALUES(1001,100000),(2002,100000) ON DUPLICATE KEY UPDATE user_id=user_id;
INSERT INTO `session`(session_id,name,type,status) VALUES(99001,'演示单聊',0,0),(99002,'演示 AI 会话',2,0) ON DUPLICATE KEY UPDATE session_id=session_id;
INSERT INTO user_session(user_id,session_id,role,status) VALUES(1001,99001,2,0),(2002,99001,2,0),(1001,99002,2,0),(111111111,99002,2,0) ON DUPLICATE KEY UPDATE user_id=user_id;
INSERT INTO friend(user_id,friend_id,status) VALUES(1001,2002,0),(2002,1001,0) ON DUPLICATE KEY UPDATE user_id=user_id;

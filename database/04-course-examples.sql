-- 对应原文 7.1 的五个用户、单聊 1、群聊 2；邮箱改为演示地址，避免向原文邮箱误发信。
-- 密码均为 123456。已有记录不覆盖，不能用于重置任何用户余额或密码。
USE Qingliao;
INSERT INTO `user`(user_id,email,password,nickname,state) VALUES
(11,'course-11@example.com','f248a27eba558ecb1d611b8d7730bff2','单聊1',0),
(12,'course-12@example.com','f248a27eba558ecb1d611b8d7730bff2','单聊2',0),
(21,'course-21@example.com','f248a27eba558ecb1d611b8d7730bff2','群聊1',0),
(22,'course-22@example.com','f248a27eba558ecb1d611b8d7730bff2','群聊2',0),
(23,'course-23@example.com','f248a27eba558ecb1d611b8d7730bff2','群聊3',0)
ON DUPLICATE KEY UPDATE user_id=user_id;
INSERT INTO `session`(session_id,name,type,status) VALUES
(1,'原文单聊',0,0),(2,'群聊',1,0) ON DUPLICATE KEY UPDATE session_id=session_id;
INSERT INTO user_session(user_id,session_id,role,status) VALUES
(11,1,2,0),(12,1,2,0),(21,2,0,0),(22,2,2,0),(23,2,2,0)
ON DUPLICATE KEY UPDATE user_id=user_id;
-- 当前代码增加了单聊好友授权；补双向正常关系，保证原文单聊示例可用于现有接口。
INSERT INTO friend(user_id,friend_id,status) VALUES(11,12,0),(12,11,0)
ON DUPLICATE KEY UPDATE user_id=user_id;

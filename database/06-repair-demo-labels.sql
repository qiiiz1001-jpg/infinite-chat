-- 修复旧版初始化时按错误字符集写入的本机试用名称。
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE `user`
SET nickname = '演示用户 A'
WHERE user_id = 1001 AND email = 'demo-a@example.com'
  AND HEX(nickname) = 'C3A6C2BCE2809DC3A7C2A4C2BAC3A7E2809DC2A8C3A6CB86C2B72041';

UPDATE `user`
SET nickname = '演示用户 B'
WHERE user_id = 2002 AND email = 'demo-b@example.com'
  AND HEX(nickname) = 'C3A6C2BCE2809DC3A7C2A4C2BAC3A7E2809DC2A8C3A6CB86C2B72042';

UPDATE `session`
SET name = '演示单聊'
WHERE session_id = 99001 AND type = 0
  AND HEX(name) = 'C3A6C2BCE2809DC3A7C2A4C2BAC3A5C28DE280A2C3A8C281C5A0';

UPDATE `session`
SET name = '轻聊 AI 会话'
WHERE session_id = 99002 AND type = 2
  AND HEX(name) = 'C3A6C2BCE2809DC3A7C2A4C2BA20414920C3A4C2BCC5A1C3A8C2AFC29D';

-- 旧默认群名已按 16 字截断，从两位试用成员重建。
UPDATE `session` AS s
JOIN (
    SELECT session_id
    FROM user_session
    GROUP BY session_id
    HAVING COUNT(*) = 2 AND COUNT(DISTINCT user_id) = 2
       AND SUM(user_id NOT IN (1001, 2002)) = 0
) AS demo ON s.session_id = demo.session_id
SET s.name = '演示用户 A、演示用户 B'
WHERE s.type = 1
  AND HEX(s.name) = 'C3A6C2BCE2809DC3A7C2A4C2BAC3A7E2809DC2A8C3A6CB86C2B72041E38081C3A6';

COMMIT;

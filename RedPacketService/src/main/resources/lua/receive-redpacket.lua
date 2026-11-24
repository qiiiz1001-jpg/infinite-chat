local poolKey   = KEYS[1]
local recordKey = KEYS[2]
local stateKey  = KEYS[3]
local grantKey  = KEYS[4]
local allowKey  = KEYS[5]
local userId    = ARGV[1]

local RECORD_TTL = 7 * 24 * 60 * 60  -- 领取记录保留七天

-- 0. 权限校验（顺序不可移动）
local grant = redis.call('HMGET', grantKey, 'sessionType', 'senderId', 'receiverId')
local sessionType = grant[1]
local senderId    = grant[2]
local receiverId  = grant[3]

if sessionType == '0' then
    -- 0.1 单聊：有且仅有 receiverId 可领；
    if receiverId ~= userId then
        if senderId == userId then
            return {-4, 1}   -- REASON_SENDER_SELF_RECEIVE
        end
        return {-4, 2}       -- REASON_NOT_THE_RECEIVER
    end
elseif sessionType == '1' then
    -- 0.2 群聊：只认发红包那一刻的成员快照；
    if redis.call('SISMEMBER', allowKey, userId) == 0 then
        return {-4, 3}       -- REASON_NOT_IN_CONVERSATION
    end
end
-- 0.3 sessionType 为 false 表示 grant 已随 TTL 消失
--     不做校验，交给下面台阶返回 -2 / -3

local expiresAt = tonumber(redis.call('HGET', grantKey, 'expiresAt') or '0')
local currentState = redis.call('GET', stateKey)
local expired = currentState == '2' or (not currentState and expiresAt > 0 and tonumber(ARGV[2]) >= expiresAt)
-- 1. 幂等性检查：判断用户是否已领取
if redis.call('HEXISTS', recordKey, userId) == 1 then
    -- 到期后重试仍返回已领取及原金额，由 Java 从领取记录读取金额。
    if expired then return {-1, 2} end
    -- 1.1 读墓碑：终态已写入，把红包此刻的状态一并带回
    local state = redis.call('GET', stateKey)
    if state then
        return {-1, tonumber(state)}
    end

    -- 1.2 墓碑还没写：池子还在说明红包仍有剩余，池子没了说明刚被抢完
    if redis.call('EXISTS', poolKey) == 1 then
        return {-1, 0}
    end
    return {-1, 1}
end

-- 2. 已有终态先返回；grant 过期不能遮蔽仍保留的领取记录和墓碑。
if expired then return {-2, 2} end
if currentState then return {-2, tonumber(currentState)} end
-- ready 仅保护新的 LPOP，阻止领取发送事务尚未提交的临时金额池。
if redis.call('HGET', grantKey, 'ready') ~= '1' then return {-3} end

-- 2. 从列表中弹出一个金额 (原子)
local amount = redis.call('LPOP', poolKey)
if not amount then
    -- 2.1 读墓碑：终态已写入，直接把死因返回给调用方
    local state = redis.call('GET', stateKey)
    if state then
        return {-2, tonumber(state)}
    end

    -- 2.2 墓碑还没写，但领取记录还在 => 刚被抢完
    if redis.call('EXISTS', recordKey) == 1 then
        return {-2, 1}
    end

    -- 2.3 池、领取记录、墓碑三者皆空 => Redis 查无此红包
    return {-3}
end

-- 3. 记录该用户已领取
redis.call('HSET', recordKey, userId, amount)
redis.call('HSET', recordKey .. ':times', userId, ARGV[2])
redis.call('EXPIRE', recordKey .. ':times', RECORD_TTL)
redis.call('EXPIRE', recordKey, RECORD_TTL, 'NX')

-- 4. 是否为最后一个
local left = redis.call('LLEN', poolKey)
local completed = 0
if left == 0 then
    completed = 1
end

-- 5. 返回抢到的金额（分）和领取完标识符
return {tonumber(amount), completed}

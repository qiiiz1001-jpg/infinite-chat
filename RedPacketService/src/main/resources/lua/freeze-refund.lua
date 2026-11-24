-- Redis 封盘结果保留，数据库事务回滚后重试仍可得到同一退款金额。
local frozen = redis.call('GET', KEYS[2])
if frozen then return tonumber(frozen) end
local amounts = redis.call('LRANGE', KEYS[1], 0, -1)
local total = 0
for _, amount in ipairs(amounts) do
    total = total + tonumber(amount)
end
redis.call('SET', KEYS[2], total, 'EX', 7 * 24 * 60 * 60)
redis.call('DEL', KEYS[1])
return total

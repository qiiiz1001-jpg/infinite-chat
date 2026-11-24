package cn.qingliao.offlinedataservice.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.common.model.dto.AssistantHistory;
import cn.qingliao.common.model.dto.ConversationSummaryInput;
import cn.qingliao.common.model.dto.MessageBody;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.common.model.vo.MessageResponse;
import cn.qingliao.common.security.CurrentUser;
import cn.qingliao.offlinedataservice.client.*;
import cn.qingliao.offlinedataservice.mapper.MessageMapper;
import cn.qingliao.offlinedataservice.model.dto.*;
import cn.qingliao.offlinedataservice.model.dto.HistoryMessageRequest;
import cn.qingliao.offlinedataservice.model.dto.OfflineMessageRequest;
import cn.qingliao.offlinedataservice.model.dto.SessionSummaryRequest;
import cn.qingliao.offlinedataservice.model.entity.Message;
import cn.qingliao.offlinedataservice.service.MessageService;
import cn.qingliao.offlinedataservice.utils.MessageResponses;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.*;

/** 最近七天先查 Redis，缓存足够则直接返回；不足或异常时回源，冷数据直接查 MySQL。 */
@Service
@Slf4j
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message>
        implements MessageService {
    @Resource private StringRedisTemplate redis;
    @Resource private UserServiceClient users;
    @Resource private AiServiceClient ai;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMessageToMySQL(MessageRequest request) {
        MessageBody body = request.getBody();
        if (body == null || request.getMessageId() == null || request.getCreatedTime() == null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息存储契约不完整");
        Message entity = new Message();
        BeanUtil.copyProperties(request, entity);
        entity.setContent(
                Integer.valueOf(3).equals(request.getType())
                        ? JSONUtil.toJsonStr(body)
                        : body.getContent());
        entity.setReplyId(body.getReplyId());
        // 主键冲突时不重复插入；不能将其它数据库异常当作消费成功。
        baseMapper.insertIdempotently(entity);
        MessageResponse response = response(entity);
        Runnable synchronize = () -> cache(response);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            synchronize.run();
                        }
                    });
        } else synchronize.run();
    }

    private void cache(MessageResponse response) {
        try {
            String key = CommonConstant.SESSION_KEY_REDIS + response.getSessionId();
            redis.opsForZSet()
                    .add(
                            key,
                            JSONUtil.toJsonStr(response),
                            Long.parseLong(response.getCreatedTime()));
            redis.opsForZSet()
                    .removeRangeByScore(
                            key,
                            0,
                            System.currentTimeMillis() - CommonConstant.SEVEN_DAYS_MILLIS - 1);
            redis.expire(key, Duration.ofDays(8));
        } catch (RuntimeException error) {
            log.warn("消息已提交，缓存更新失败，messageId={}", response.getMessageId(), error);
        }
    }

    @Override
    public Map<Long, List<MessageResponse>> getOfflineMessages(OfflineMessageRequest request) {
        Long userId = CurrentUser.id();
        if (request.getOfflineTime() == null || request.getOfflineTime() < 0)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        Map<Long, List<MessageResponse>> result = new LinkedHashMap<>();
        long hotBoundary = System.currentTimeMillis() - CommonConstant.SEVEN_DAYS_MILLIS;
        for (Long sessionId : users.getSessionIdsByUserId(userId)) {
            List<MessageResponse> messages =
                    getMessagesAfter(sessionId, request.getOfflineTime(), hotBoundary);
            if (!messages.isEmpty()) result.put(sessionId, messages);
        }
        return result;
    }

    /** 原文 9.6.5：热区读 Redis，跨七天时再查询 MySQL 冷区，最后正序合并。 */
    private List<MessageResponse> getMessagesAfter(
            Long sessionId, long afterTime, long hotBoundary) {
        if (afterTime == Long.MAX_VALUE) return List.of();
        List<MessageResponse> hot;
        try {
            // 跨冷热边界时包含边界毫秒；冷区严格小于边界，避免原文两端都排除造成漏数。
            hot = getMessagesFromRedisAfter(sessionId, Math.max(afterTime + 1, hotBoundary));
        } catch (RuntimeException error) {
            log.debug("离线消息缓存不可用，按原始离线时间回源", error);
            return getMessagesFromMySQLAfter(sessionId, afterTime, null);
        }
        // 空缓存可能是过期/未同步，不直接判定没有离线消息。
        if (hot.isEmpty()) return getMessagesFromMySQLAfter(sessionId, afterTime, null);
        Map<Long, MessageResponse> merged = new HashMap<>();
        hot.forEach(value -> merged.put(value.getMessageId(), value));
        if (afterTime < hotBoundary)
            getMessagesFromMySQLAfter(sessionId, afterTime, hotBoundary)
                    .forEach(value -> merged.put(value.getMessageId(), value));
        return merged.values().stream()
                .sorted(
                        Comparator.comparingLong(
                                        (MessageResponse value) ->
                                                Long.parseLong(value.getCreatedTime()))
                                .thenComparing(MessageResponse::getMessageId))
                .toList();
    }

    private List<MessageResponse> getMessagesFromRedisAfter(Long sessionId, long minTime) {
        Set<String> values =
                redis.opsForZSet()
                        .rangeByScore(
                                CommonConstant.SESSION_KEY_REDIS + sessionId,
                                minTime,
                                Double.MAX_VALUE);
        if (values == null) return List.of();
        List<MessageResponse> result = new ArrayList<>();
        for (String json : values) {
            MessageResponse value = JSONUtil.toBean(json, MessageResponse.class);
            if (value.getMessageId() == null
                    || !sessionId.equals(value.getSessionId())
                    || value.getBody() == null
                    || Long.parseLong(value.getCreatedTime()) < minTime)
                throw new IllegalStateException("离线缓存消息格式不完整");
            result.add(value);
        }
        return result;
    }

    private List<MessageResponse> getMessagesFromMySQLAfter(
            Long sessionId, long afterTime, Long beforeTime) {
        LambdaQueryWrapper<Message> query =
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .gt(Message::getCreatedTime, new Date(afterTime));
        if (beforeTime != null) query.lt(Message::getCreatedTime, new Date(beforeTime));
        return list(query.orderByAsc(Message::getCreatedTime).orderByAsc(Message::getMessageId))
                .stream()
                .map(this::response)
                .toList();
    }

    @Override
    public List<MessageResponse> getHistoryMessages(HistoryMessageRequest request) {
        requireMembership(request.getSessionId());
        return findHistory(request);
    }

    @Override
    public AssistantHistory.Page getAssistantHistory(AssistantHistory.Query query) {
        requireAssistantHistoryAccess(query);

        var request = new HistoryMessageRequest();
        request.setSessionId(query.sessionId());
        request.setBeforeTime(
                query.beforeTime() == null ? System.currentTimeMillis() + 1 : query.beforeTime());
        request.setBeforeMessageId(query.beforeMessageId());
        request.setLimit(21);
        var page = findHistory(request);
        int count = Math.min(20, page.size());
        var names = count == 0 ? Map.<Long, String>of() : users.getUserNickName(query.sessionId());
        var entries = new java.util.ArrayList<AssistantHistory.Entry>(count);
        for (int i = count - 1; i >= 0; i--) {
            var message = page.get(i);
            String sender = names == null ? null : names.get(message.getSenderId());
            String text = "［非文本消息］";
            if (Integer.valueOf(0).equals(message.getType())) {
                text =
                        message.getBody() == null
                                ? message.getContent()
                                : message.getBody().getContent();
            }
            if (text == null) text = "";
            if (text.length() > 500) {
                int end = Character.isHighSurrogate(text.charAt(499)) ? 499 : 500;
                text = text.substring(0, end) + "…";
            }
            entries.add(
                    new AssistantHistory.Entry(
                            sender == null ? "会话成员" : sender,
                            Long.parseLong(message.getCreatedTime()),
                            text));
        }
        requireAssistantHistoryAccess(query);
        var oldest = page.size() > count ? page.get(count - 1) : null;
        return new AssistantHistory.Page(
                entries,
                oldest == null ? null : Long.valueOf(oldest.getCreatedTime()),
                oldest == null ? null : oldest.getMessageId().toString());
    }

    private void requireAssistantHistoryAccess(AssistantHistory.Query query) {
        if (query.userId() <= 0 || query.sessionId() <= 0)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        var access = users.knowledgeAccess(query.userId(), query.sessionId());
        if (access == null || !access.accessible())
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
    }

    private List<MessageResponse> findHistory(HistoryMessageRequest request) {
        int limit = request.getLimit() == null ? 20 : request.getLimit();
        if (limit < 1
                || limit > 100
                || request.getBeforeTime() == null
                || request.getBeforeTime() < 0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        long hotBoundary = System.currentTimeMillis() - CommonConstant.SEVEN_DAYS_MILLIS;
        if (request.getBeforeTime() > hotBoundary) {
            List<MessageResponse> hot = getMessagesFromRedisBefore(request, hotBoundary, limit);
            // 恢复原文缓存命中分支：满足这一页时不执行消息表 SELECT。
            if (hot.size() == limit) return hot;
        }
        // 不足时仍用原始游标回源，不能跳到七天前，否则会漏掉热区内未同步的消息。
        return getMessagesFromMySQLBefore(request, limit);
    }

    /** 多取一条检测分页末尾同毫秒并列；无法确定 ID 顺序时回源，避免漏页。 */
    private List<MessageResponse> getMessagesFromRedisBefore(
            HistoryMessageRequest request, long hotBoundary, int limit) {
        try {
            long maxScore =
                    request.getBeforeMessageId() == null
                            ? request.getBeforeTime() - 1
                            : request.getBeforeTime();
            Set<String> hot =
                    redis.opsForZSet()
                            .reverseRangeByScore(
                                    CommonConstant.SESSION_KEY_REDIS + request.getSessionId(),
                                    hotBoundary,
                                    maxScore,
                                    0,
                                    limit + 1L);
            if (hot == null || hot.isEmpty()) return List.of();
            Map<Long, MessageResponse> unique = new HashMap<>();
            long earliestFetched = Long.MAX_VALUE;
            for (String item : hot) {
                MessageResponse value = JSONUtil.toBean(item, MessageResponse.class);
                long time = Long.parseLong(value.getCreatedTime());
                if (value.getMessageId() == null
                        || !request.getSessionId().equals(value.getSessionId())
                        || value.getBody() == null
                        || time < hotBoundary
                        || time > maxScore) return List.of();
                earliestFetched = Math.min(earliestFetched, time);
                if (time < request.getBeforeTime()
                        || (request.getBeforeMessageId() != null
                                && value.getMessageId() < request.getBeforeMessageId()))
                    unique.put(value.getMessageId(), value);
            }
            List<MessageResponse> sorted =
                    unique.values().stream()
                            .sorted(
                                    Comparator.comparingLong(
                                                    (MessageResponse value) ->
                                                            Long.parseLong(value.getCreatedTime()))
                                            .thenComparing(MessageResponse::getMessageId)
                                            .reversed())
                            .toList();
            if (sorted.size() < limit) return List.of();
            // ZSET 同 score 按 JSON 字节排序，不是按 messageId；截断在并列组内时查库。
            if (hot.size() == limit + 1
                    && earliestFetched == Long.parseLong(sorted.get(limit - 1).getCreatedTime()))
                return List.of();
            return sorted.subList(0, limit);
        } catch (RuntimeException error) {
            log.debug("历史缓存不可用，使用 MySQL", error);
            return List.of();
        }
    }

    /** 查询完整的一页作为回源结果，覆盖热区缺口和跨七天边界，保持复合游标。 */
    private List<MessageResponse> getMessagesFromMySQLBefore(
            HistoryMessageRequest request, int limit) {
        LambdaQueryWrapper<Message> query =
                new LambdaQueryWrapper<Message>().eq(Message::getSessionId, request.getSessionId());
        Date before = new Date(request.getBeforeTime());
        if (request.getBeforeMessageId() == null) query.lt(Message::getCreatedTime, before);
        else
            query.and(
                    w ->
                            w.lt(Message::getCreatedTime, before)
                                    .or()
                                    .eq(Message::getCreatedTime, before)
                                    .lt(Message::getMessageId, request.getBeforeMessageId()));
        query.orderByDesc(Message::getCreatedTime)
                .orderByDesc(Message::getMessageId)
                .last("LIMIT " + limit);
        return list(query).stream().map(this::response).toList();
    }

    @Override
    public String getSummary(SessionSummaryRequest request) {
        requireMembership(request.getSessionId());
        int hours = request.getHours() == null ? 24 : request.getHours();
        if (hours < 1) throw new BusinessException(ErrorCode.PARAMS_ERROR, "时间范围必须为正整数小时");
        List<Message> messages =
                list(
                        new LambdaQueryWrapper<Message>()
                                .eq(Message::getSessionId, request.getSessionId())
                                .ge(
                                        Message::getCreatedTime,
                                        new Date(System.currentTimeMillis() - hours * 3600000L))
                                .orderByDesc(Message::getCreatedTime)
                                .orderByDesc(Message::getMessageId));
        if (messages.isEmpty()) return "所选时间范围内暂无聊天记录。";
        Map<Long, String> names = users.getUserNickName(request.getSessionId());
        StringBuilder history = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            history.append("[")
                    .append(
                            names.getOrDefault(
                                    message.getSenderId(), message.getSenderId().toString()))
                    .append("] ")
                    .append(
                            cn.qingliao.common.utils.FormatDateUtil.formatDate(
                                    message.getCreatedTime()))
                    .append("：")
                    .append(message.getContent())
                    .append('\n');
        }
        String summary;
        try {
            var response = ai.chatSummary(new ConversationSummaryInput(history.toString()));
            summary = response != null && response.getCode() == 200 ? response.getData() : null;
        } catch (feign.FeignException error) {
            log.warn("会话总结请求失败 (HTTP {})", error.status());
            summary = null;
        }
        if (summary == null || summary.isBlank())
            throw new BusinessException(ErrorCode.SYSTEM_BUSY, "会话总结暂不可用，请稍后重试。");
        return summary;
    }

    private void requireMembership(Long sessionId) {
        if (sessionId == null || !users.getSessionIdsByUserId(CurrentUser.id()).contains(sessionId))
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有该会话的访问权限");
    }

    private MessageResponse response(Message message) {
        return MessageResponses.fromEntity(message);
    }
}

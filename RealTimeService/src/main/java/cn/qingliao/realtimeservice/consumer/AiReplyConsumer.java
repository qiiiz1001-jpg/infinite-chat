package cn.qingliao.realtimeservice.consumer;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.common.model.dto.MessageBody;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.realtimeservice.client.AiServiceClient;
import cn.qingliao.realtimeservice.utils.SnowflakeDynamicUtil;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;

/** AI 请求由一个稳定消费组处理，防止在线推送广播导致每个节点重复调用模型。 */
@Service
@RequiredArgsConstructor
public class AiReplyConsumer {
    private final AiServiceClient ai;
    private final KafkaTemplate<String, String> kafka;
    private final SnowflakeDynamicUtil ids;
    private final StringRedisTemplate redis;

    @org.springframework.beans.factory.annotation.Value("${ai.enabled:true}")
    private boolean aiEnabled;

    @KafkaListener(
            topics = "ai-request-topic",
            groupId = "infinite-chat-ai",
            properties = {"max.poll.records=1", "max.poll.interval.ms=600000"})
    public void consume(String payload) {
        MessageRequest request = JSONUtil.toBean(payload, MessageRequest.class);
        String key = "ai:reply:" + request.getMessageId();
        String cached = redis.opsForValue().get(key);
        if (cached == null) {
            ChatRequest prompt = new ChatRequest();
            prompt.setUserId(request.getSenderId());
            prompt.setSessionId(request.getSessionId());
            prompt.setReferenceSessionId(request.getReferenceSessionId());
            prompt.setPrompt(request.getBody().getContent());
            MessageBody body = new MessageBody();
            body.setContent(
                    aiEnabled ? ai.chat(prompt) : "【系统提示】当前只进行中间件联调，模型服务尚未配置。知识问答将在模型联调后启用。");
            MessageRequest reply = new MessageRequest();
            reply.setMessageId(ids.nextId());
            reply.setSessionId(request.getSessionId());
            reply.setSenderId(CommonConstant.AI_ID);
            reply.setReceiverId(request.getSenderId());
            reply.setSessionType(2);
            reply.setType(0);
            reply.setBody(body);
            reply.setContent(body.getContent());
            reply.setCreatedTime(new Date());
            reply.setClientMessageId("ai:" + request.getMessageId());
            cached = JSONUtil.toJsonStr(reply);
            redis.opsForValue().setIfAbsent(key, cached, Duration.ofDays(7));
            String winner = redis.opsForValue().get(key);
            if (winner != null) cached = winner;
        }
        // Kafka 确认发生在两路投递完成后；重试复用同一回复，仍需存储侧幂等。
        kafka.send(
                        CommonConstant.KAFKA_MESSAGE_TOPIC_STORE,
                        request.getSessionId().toString(),
                        cached)
                .join();
        kafka.send(
                        CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH,
                        request.getSessionId().toString(),
                        cached)
                .join();
    }
}

package cn.qingliao.realtimeservice.consumer;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.common.model.vo.MessageResponse;
import cn.qingliao.realtimeservice.client.UserServiceClient;
import cn.qingliao.realtimeservice.websocket.ChannelManager;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConsumerMessageService {
    @Resource private UserServiceClient users;

    /** 各实时节点独立消费组，再按本机连接过滤；避免同组分摊后查错节点。 */
    @KafkaListener(
            topics = CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH,
            groupId = "infinite-chat-push-${chat.node-id:node-1}")
    public void consume(String payload) {
        MessageRequest request = JSONUtil.toBean(payload, MessageRequest.class);
        MessageResponse response = createMessageResponse(request);
        if (Integer.valueOf(1).equals(request.getSessionType())) {
            for (Long userId : users.getUserIdBySessionId(request.getSessionId()))
                pushMessageToUser(response, userId);
        } else {
            pushMessageToUser(response, request.getSenderId());
            if (request.getReceiverId() != null
                    && !request.getReceiverId().equals(request.getSenderId()))
                pushMessageToUser(response, request.getReceiverId());
        }
    }

    public MessageResponse createMessageResponse(MessageRequest request) {
        MessageResponse response = new MessageResponse();
        BeanUtil.copyProperties(request, response);
        response.setCreatedTime(Long.toString(request.getCreatedTime().getTime()));
        return response;
    }

    /** 本机写操作完成不代表已读；离线客户端通过存储服务补拉。 */
    public void pushMessageToUser(MessageResponse response, Long userId) {
        Channel channel = ChannelManager.getChannelByUserId(userId.toString());
        if (channel != null && channel.isActive()) {
            // 异步写操作
            channel.writeAndFlush(
                            new TextWebSocketFrame(
                                    cn.qingliao.common.utils.WireJson.write(response)))
                    .addListener(
                            future -> {
                                if (!future.isSuccess())
                                    log.warn(
                                            "推送失败，messageId={}",
                                            response.getMessageId(),
                                            future.cause());
                            });
        }
    }
}

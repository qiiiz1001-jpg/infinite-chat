package cn.qingliao.realtimeservice.consumer;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.utils.WireJson;
import cn.qingliao.realtimeservice.websocket.ChannelManager;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 推送与存储使用独立消费组；本节点没有连接时，由存储消费组保存通知供离线查询。 */
@Component
public class SystemNotificationConsumer {
    @KafkaListener(
            topics = CommonConstant.KAFKA_SYSTEM_NOTIFICATION_TOPIC,
            groupId = "infinite-chat-system-push-${chat.node-id:node-1}")
    public void consumeSystemNotification(String payload) {
        String receiver = JSONUtil.parseObj(payload).getStr("receiverId");
        Channel channel = ChannelManager.getChannelByUserId(receiver);
        if (channel != null && channel.isActive())
            channel.writeAndFlush(new TextWebSocketFrame(WireJson.write(WireJson.read(payload))));
    }
}

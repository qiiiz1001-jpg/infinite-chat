package cn.qingliao.offlinedataservice.consumer;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.offlinedataservice.service.MessageService;

import jakarta.annotation.Resource;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ConsumerOfflineService {

    @KafkaListener(topics = "store-topic", groupId = "infinite-chat-store-group")
    public void consume(String message) {
        System.out.println("Consumed message store: " + message);
        MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
        messageService.saveMessageToMySQL(messageRequest);
        System.out.println(messageRequest);
    }

    @Resource private MessageService messageService;
}

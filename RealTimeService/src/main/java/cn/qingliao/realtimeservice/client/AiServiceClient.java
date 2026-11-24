package cn.qingliao.realtimeservice.client;

import cn.qingliao.common.model.dto.ChatRequest;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "AiService", url = "${services.aiservice.url:http://localhost:8105}")
public interface AiServiceClient {

    @PostMapping("/api/ai/chat")
    String chat(@RequestBody ChatRequest chatRequest);
}

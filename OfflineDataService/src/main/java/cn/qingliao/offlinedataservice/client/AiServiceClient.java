package cn.qingliao.offlinedataservice.client;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.model.dto.ConversationSummaryInput;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "AiService", url = "${services.aiservice.url:http://localhost:8105}")
public interface AiServiceClient {

    @PostMapping("/api/ai/internal/summary")
    BaseResponse<String> chatSummary(@RequestBody ConversationSummaryInput input);
}

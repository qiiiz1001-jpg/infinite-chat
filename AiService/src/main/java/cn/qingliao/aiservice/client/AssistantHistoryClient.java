package cn.qingliao.aiservice.client;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.model.dto.AssistantHistory;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "OfflineDataService",
        url = "${services.offlinedataservice.url:http://localhost:8101}")
public interface AssistantHistoryClient {
    @PostMapping("/api/message/internal/assistant-history")
    BaseResponse<AssistantHistory.Page> history(@RequestBody AssistantHistory.Query query);
}

package cn.qingliao.realtimeservice.client;

import cn.qingliao.common.model.dto.KnowledgeSession;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "UserService", url = "${services.userservice.url:http://localhost:8104}")
public interface UserServiceClient {
    @GetMapping("/api/user/internal/knowledge-access")
    KnowledgeSession knowledgeAccess(
            @RequestParam("userId") Long userId, @RequestParam("sessionId") Long sessionId);

    @GetMapping("/api/user/internal/can-send")
    boolean canSend(
            @RequestParam("userId") Long userId,
            @RequestParam("sessionId") Long sessionId,
            @RequestParam("sessionType") Integer sessionType,
            @RequestParam(value = "receiverId", required = false) Long receiverId);

    @GetMapping("/api/user/internal/receivers")
    List<Long> getUserIdBySessionId(@RequestParam("sessionId") Long sessionId);
}

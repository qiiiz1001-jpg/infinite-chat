package cn.qingliao.offlinedataservice.client;

import cn.qingliao.common.model.dto.KnowledgeSession;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(name = "UserService", url = "${services.userservice.url:http://localhost:8104}")
public interface UserServiceClient {
    @GetMapping("/api/user/internal/knowledge-access")
    KnowledgeSession knowledgeAccess(
            @RequestParam("userId") Long userId, @RequestParam("sessionId") Long sessionId);

    @GetMapping("/api/user/internal/sessions")
    List<Long> getSessionIdsByUserId(@RequestParam("userId") Long userId);

    @GetMapping("/api/user/internal/nicknames")
    Map<Long, String> getUserNickName(@RequestParam("sessionId") Long sessionId);
}

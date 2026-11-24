package cn.qingliao.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "UserService", url = "${services.userservice.url:http://localhost:8104}")
public interface SessionAccessClient {
    @GetMapping("/api/user/internal/knowledge-access")
    cn.qingliao.common.model.dto.KnowledgeSession knowledgeAccess(
            @RequestParam("userId") Long userId, @RequestParam("sessionId") Long sessionId);

    @GetMapping("/api/user/internal/sessions")
    List<Long> sessions(@RequestParam("userId") Long userId);
}

package cn.qingliao.aiservice.client;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.model.dto.AgentActions.*;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "agent-actions", url = "${services.userservice.url:http://localhost:8104}")
public interface AgentActionClient {
    @GetMapping("/api/user/internal/agent/sessions")
    BaseResponse<List<SessionOption>> sessions(
            @RequestParam("actorId") long actorId, @RequestParam("keyword") String keyword);

    @GetMapping("/api/user/internal/agent/actions")
    BaseResponse<List<Action>> list(@RequestParam("actorId") long actorId);

    @PostMapping("/api/user/internal/agent/actions")
    BaseResponse<Action> draft(@RequestParam("actorId") long actorId, @RequestBody Draft draft);

    @PostMapping("/api/user/internal/agent/reminders")
    BaseResponse<Action> scheduleReminder(
            @RequestParam("actorId") long actorId, @RequestBody Draft draft);

    @PatchMapping("/api/user/internal/agent/actions/{id}")
    BaseResponse<Action> edit(
            @RequestParam("actorId") long actorId,
            @PathVariable("id") long id,
            @RequestBody Edit edit);

    @PostMapping("/api/user/internal/agent/actions/{id}/cancel")
    BaseResponse<Action> cancel(
            @RequestParam("actorId") long actorId,
            @PathVariable("id") long id,
            @RequestBody Decision decision);
}

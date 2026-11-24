package cn.qingliao.userservice.agent;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ResultUtils;
import cn.qingliao.common.model.dto.AgentActions.*;
import cn.qingliao.common.security.CurrentUser;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 对外身份一律来自登录态；确认执行不暴露给模型工具。 */
@RestController
@RequestMapping("/api/user/agent")
@RequiredArgsConstructor
public class AgentActionController {
    private final AgentActionService service;

    @GetMapping("/sessions")
    public BaseResponse<List<SessionOption>> sessions(
            @RequestParam(defaultValue = "") String keyword) {
        return ResultUtils.success(service.sessions(CurrentUser.id(), keyword));
    }

    @GetMapping("/actions")
    public BaseResponse<List<Action>> list() {
        return ResultUtils.success(service.list(CurrentUser.id()));
    }

    @GetMapping("/reminders")
    public BaseResponse<List<Action>> reminders() {
        return ResultUtils.success(service.reminders(CurrentUser.id()));
    }

    @GetMapping("/actions/{id}")
    public BaseResponse<Action> get(@PathVariable long id) {
        return ResultUtils.success(service.owned(CurrentUser.id(), id));
    }

    @PostMapping("/actions")
    public BaseResponse<Action> draft(@RequestBody Draft draft) {
        return ResultUtils.success(service.draft(CurrentUser.id(), draft));
    }

    @PostMapping("/actions/{id}/confirm")
    public BaseResponse<Action> confirm(@PathVariable long id, @RequestBody Decision decision) {
        return ResultUtils.success(service.confirm(CurrentUser.id(), id, decision.version()));
    }

    @PatchMapping("/actions/{id}")
    public BaseResponse<Action> edit(@PathVariable long id, @RequestBody Edit edit) {
        return ResultUtils.success(service.edit(CurrentUser.id(), id, edit));
    }

    @PostMapping("/actions/{id}/cancel")
    public BaseResponse<Action> cancel(@PathVariable long id, @RequestBody Decision decision) {
        return ResultUtils.success(service.cancel(CurrentUser.id(), id, decision.version()));
    }

    @PostMapping("/actions/{id}/retry")
    public BaseResponse<Action> retry(@PathVariable long id, @RequestBody Decision decision) {
        return ResultUtils.success(service.retry(CurrentUser.id(), id, decision.version()));
    }
}

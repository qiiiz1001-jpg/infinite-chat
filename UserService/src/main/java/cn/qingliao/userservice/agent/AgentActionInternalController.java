package cn.qingliao.userservice.agent;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.common.ResultUtils;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.model.dto.AgentActions.*;
import cn.qingliao.common.security.CurrentUser;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user/internal/agent")
@RequiredArgsConstructor
public class AgentActionInternalController {
    private final AgentActionService service;

    private long actor(long actorId) {
        if (!CurrentUser.internal() || actorId <= 0)
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return actorId;
    }

    @GetMapping("/sessions")
    public BaseResponse<List<SessionOption>> sessions(
            @RequestParam long actorId, @RequestParam(defaultValue = "") String keyword) {
        return ResultUtils.success(service.sessions(actor(actorId), keyword));
    }

    @GetMapping("/actions")
    public BaseResponse<List<Action>> list(@RequestParam long actorId) {
        return ResultUtils.success(service.list(actor(actorId)));
    }

    @PostMapping("/actions")
    public BaseResponse<Action> draft(@RequestParam long actorId, @RequestBody Draft draft) {
        return ResultUtils.success(service.draft(actor(actorId), draft));
    }

    @PostMapping("/reminders")
    public BaseResponse<Action> scheduleReminder(
            @RequestParam long actorId, @RequestBody Draft draft) {
        long owner = actor(actorId);
        if (draft == null || !"REMINDER".equals(draft.kind())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅本人提醒可直接排期");
        }
        Action action = service.draft(owner, draft);
        if ("DRAFT".equals(action.getStatus())) {
            action = service.confirm(owner, action.getActionId(), action.getVersion());
        }
        return ResultUtils.success(action);
    }

    @PatchMapping("/actions/{id}")
    public BaseResponse<Action> edit(
            @RequestParam long actorId, @PathVariable long id, @RequestBody Edit edit) {
        return ResultUtils.success(service.edit(actor(actorId), id, edit));
    }

    @PostMapping("/actions/{id}/cancel")
    public BaseResponse<Action> cancel(
            @RequestParam long actorId, @PathVariable long id, @RequestBody Decision decision) {
        return ResultUtils.success(service.cancel(actor(actorId), id, decision.version()));
    }
}

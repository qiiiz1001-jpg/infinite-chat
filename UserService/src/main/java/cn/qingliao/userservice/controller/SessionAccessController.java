package cn.qingliao.userservice.controller;

import cn.qingliao.userservice.agent.SessionSendAccess;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/** 内部 RPC 身份由内部认证头确认，权限逻辑与 Agent 共用。 */
@RestController
@RequestMapping("/api/user/internal")
@RequiredArgsConstructor
public class SessionAccessController {
    private final SessionSendAccess access;

    @GetMapping("/can-send")
    public boolean canSend(
            @RequestParam Long userId,
            @RequestParam Long sessionId,
            @RequestParam Integer sessionType,
            @RequestParam(required = false) Long receiverId) {
        return access.canSend(userId, sessionId, sessionType, receiverId);
    }
}

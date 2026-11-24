package cn.qingliao.userservice.controller;

import cn.qingliao.userservice.service.UserSessionService;

import jakarta.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user")
public class UserSessionController {

    @Resource private UserSessionService userSessionService;
    @Resource private cn.qingliao.userservice.service.SessionService sessionService;
    @Resource private cn.qingliao.userservice.service.UserService userService;

    @GetMapping("/internal/knowledge-access")
    public cn.qingliao.common.model.dto.KnowledgeSession knowledgeAccess(
            @RequestParam Long userId, @RequestParam Long sessionId) {
        var user = userService.getById(userId);
        var session = sessionService.getById(sessionId);
        var active =
                userSessionService.list(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                        cn.qingliao.userservice.model.entity.UserSession>()
                                .eq(
                                        cn.qingliao.userservice.model.entity.UserSession
                                                ::getSessionId,
                                        sessionId)
                                .eq(
                                        cn.qingliao.userservice.model.entity.UserSession::getStatus,
                                        0));
        boolean allowed =
                user != null
                        && Integer.valueOf(0).equals(user.getState())
                        && session != null
                        && Integer.valueOf(0).equals(session.getStatus())
                        && active.stream().anyMatch(m -> userId.equals(m.getUserId()));
        boolean assistant =
                allowed
                        && Integer.valueOf(2).equals(session.getType())
                        && active.size() == 2
                        && active.stream()
                                .anyMatch(
                                        m ->
                                                cn.qingliao.common.constant.CommonConstant.AI_ID
                                                        .equals(m.getUserId()));
        return new cn.qingliao.common.model.dto.KnowledgeSession(allowed, assistant);
    }

    @GetMapping("/internal/receivers")
    List<Long> getUserIdBySessionId(@RequestParam("sessionId") Long sessionId) {
        return userSessionService.getUserIdBySessionId(sessionId);
    }

    @GetMapping("/internal/sessions")
    List<Long> getSessionIdsByUserId(@RequestParam("userId") Long userId) {
        return userSessionService.getSessionIdsByUserId(userId);
    }
}

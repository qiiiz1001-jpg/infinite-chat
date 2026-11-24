package cn.qingliao.userservice.agent;

import cn.qingliao.userservice.model.entity.Friend;
import cn.qingliao.userservice.model.entity.UserSession;
import cn.qingliao.userservice.service.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.List;

/** 普通消息与 Agent 共用成员、用户状态和方向性拉黑校验。 */
@Service
@RequiredArgsConstructor
public class SessionSendAccess {
    private final SessionService sessions;
    private final UserSessionService members;
    private final UserService users;
    private final FriendService friends;

    public boolean canSend(Long userId, Long sessionId, Integer sessionType, Long receiverId) {
        var sender = users.getById(userId);
        if (sender == null || !Integer.valueOf(0).equals(sender.getState())) return false;
        if (Integer.valueOf(0).equals(sessionType) && receiverId != null) {
            var receiver = users.getById(receiverId);
            if (receiver == null || !Integer.valueOf(0).equals(receiver.getState())) return false;
            for (Long[] pair : new Long[][] {{userId, receiverId}, {receiverId, userId}}) {
                if (friends.count(
                                new LambdaQueryWrapper<Friend>()
                                        .eq(Friend::getUserId, pair[0])
                                        .eq(Friend::getFriendId, pair[1])
                                        .in(
                                                Friend::getStatus,
                                                pair[0].equals(userId)
                                                        ? List.of(0, 1)
                                                        : List.of(0)))
                        == 0) return false;
            }
        }
        var s = sessions.getById(sessionId);
        if (s == null
                || !Integer.valueOf(0).equals(s.getStatus())
                || !java.util.Objects.equals(sessionType, s.getType())) return false;
        List<UserSession> active =
                members.list(
                        new LambdaQueryWrapper<UserSession>()
                                .eq(UserSession::getSessionId, sessionId)
                                .eq(UserSession::getStatus, 0));
        if (active.stream().noneMatch(m -> userId.equals(m.getUserId()))) return false;
        if (Integer.valueOf(1).equals(sessionType)) return receiverId == null;
        return receiverId != null
                && !userId.equals(receiverId)
                && active.stream().anyMatch(m -> receiverId.equals(m.getUserId()));
    }
}

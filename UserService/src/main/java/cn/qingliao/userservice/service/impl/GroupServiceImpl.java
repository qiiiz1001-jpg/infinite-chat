package cn.qingliao.userservice.service.impl;

import cn.hutool.json.JSONUtil;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.constant.CommonConstant;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.model.dto.PageResponse;
import cn.qingliao.common.security.CurrentUser;
import cn.qingliao.common.utils.SnowflakeUtil;
import cn.qingliao.userservice.mapper.*;
import cn.qingliao.userservice.mapper.FriendMapper;
import cn.qingliao.userservice.mapper.SessionMapper;
import cn.qingliao.userservice.mapper.UserMapper;
import cn.qingliao.userservice.mapper.UserSessionMapper;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.GroupMemberDTO;
import cn.qingliao.userservice.model.dto.SystemNotificationMessage;
import cn.qingliao.userservice.model.dto.UserGroupDTO;
import cn.qingliao.userservice.model.dto.request.*;
import cn.qingliao.userservice.model.dto.request.CreateGroupRequest;
import cn.qingliao.userservice.model.dto.request.ExitGroupRequest;
import cn.qingliao.userservice.model.dto.request.InviteGroupRequest;
import cn.qingliao.userservice.model.dto.request.KickGroupMembersRequest;
import cn.qingliao.userservice.model.dto.response.*;
import cn.qingliao.userservice.model.dto.response.CreateGroupResponse;
import cn.qingliao.userservice.model.dto.response.InviteGroupResponse;
import cn.qingliao.userservice.model.dto.response.KickGroupMembersResponse;
import cn.qingliao.userservice.model.entity.*;
import cn.qingliao.userservice.model.entity.Friend;
import cn.qingliao.userservice.model.entity.Session;
import cn.qingliao.userservice.model.entity.User;
import cn.qingliao.userservice.model.entity.UserSession;
import cn.qingliao.userservice.service.*;
import cn.qingliao.userservice.service.GroupService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {
    private final SessionMapper sessions;
    private final UserSessionMapper members;
    private final FriendMapper friends;
    private final UserMapper users;
    private final KafkaTemplate<String, String> kafka;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateGroupResponse createGroup(CreateGroupRequest request) {
        Long creator = CurrentUser.id();
        activeUser(creator);
        List<Long> failed = new ArrayList<>();
        List<Long> valid = eligibleFriends(creator, request.getMemberIds(), failed);
        if (valid.size() < 2) throw new BusinessException(ErrorCode.PARAMS_ERROR, "新建群聊至少需要两位有效好友");
        String name = Objects.toString(request.getName(), "").strip();
        if (name.length() > 32) throw new BusinessException(ErrorCode.PARAMS_ERROR, "群名称最多 32 个字符");
        List<Long> all = new ArrayList<>();
        all.add(creator);
        all.addAll(valid);
        Session session = new Session();
        session.setSessionId(SnowflakeUtil.nextId());
        session.setType(1);
        session.setStatus(0);
        session.setName(name.isEmpty() ? generateGroupName(all) : name);
        session.setAvatar(DEFAULT_GROUP_AVATAR_URL);
        session.setCreatedTime(new Date());
        session.setUpdatedTime(new Date());
        sessions.insert(session);
        add(session.getSessionId(), creator, 0);
        valid.forEach(userId -> add(session.getSessionId(), userId, 2));
        // 原文响应还包含非法成员及通知失败成员；提交后等待通知结果再结束本次响应。
        notifyMembers(
                session.getSessionId(),
                103,
                valid,
                newGroupBody(session, creator, all.size()),
                failed);
        CreateGroupResponse response = new CreateGroupResponse();
        response.setCreatorId(creator);
        response.setSessionId(session.getSessionId());
        response.setSessionName(session.getName());
        response.setSessionType(1);
        response.setAvatar(session.getAvatar());
        response.setFailedMemberIds(failed);
        response.setMemberIds(all);
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InviteGroupResponse inviteGroup(InviteGroupRequest request) {
        Long actor = CurrentUser.id();
        Session session = lock(request.getSessionId());
        UserSession operator = membership(session.getSessionId(), actor);
        if (operator.getRole() > 1)
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有群主和管理员可以邀请");
        List<Long> failed = new ArrayList<>();
        List<Long> valid = eligibleFriends(actor, request.getInviteeIds(), failed);
        Set<Long> existing =
                new HashSet<>(
                        activeMembers(session.getSessionId()).stream()
                                .map(UserSession::getUserId)
                                .toList());
        List<Long> success = new ArrayList<>();
        for (Long id : valid) {
            if (existing.contains(id)) failed.add(id);
            else {
                add(session.getSessionId(), id, 2);
                success.add(id);
            }
        }
        if (success.isEmpty()) throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有可新增的群成员");
        List<UserSession> current = activeMembers(session.getSessionId());
        Long creator =
                current.stream()
                        .filter(m -> m.getRole() == 0)
                        .map(UserSession::getUserId)
                        .findFirst()
                        .orElse(null);
        notifyMembers(
                session.getSessionId(),
                103,
                success,
                newGroupBody(session, creator, current.size()),
                null);
        InviteGroupResponse response = new InviteGroupResponse();
        response.setSuccessIds(success);
        response.setFailedIds(failed);
        return response;
    }

    /** 同一会话的变更先锁会话行，串行处理成员修改；角色判断不能来自请求参数。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KickGroupMembersResponse kick(KickGroupMembersRequest request) {
        lock(request.getSessionId());
        Long actor = CurrentUser.id();
        UserSession operator = membership(request.getSessionId(), actor);
        if (operator.getRole() > 1) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        if (request.getMemberIds() == null || request.getMemberIds().isEmpty())
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Long> before =
                activeMembers(request.getSessionId()).stream().map(UserSession::getUserId).toList();
        List<Long> removed = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(request.getMemberIds())) {
            UserSession target = findMember(request.getSessionId(), id);
            if (target != null && canKick(operator.getRole(), target.getRole())) {
                members.delete(
                        new LambdaQueryWrapper<UserSession>()
                                .eq(UserSession::getSessionId, request.getSessionId())
                                .eq(UserSession::getUserId, id));
                removed.add(id);
            }
        }
        if (!removed.isEmpty())
            notifyMembers(request.getSessionId(), 104, before, kickBody(removed, actor), null);
        KickGroupMembersResponse response = new KickGroupMembersResponse();
        response.setSuccessIds(removed);
        return response;
    }

    public static boolean canKick(int operatorRole, int targetRole) {
        return (operatorRole == 0 && targetRole != 0) || (operatorRole == 1 && targetRole == 2);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean exit(ExitGroupRequest request) {
        lock(request.getSessionId());
        Long actor = CurrentUser.id();
        UserSession member = membership(request.getSessionId(), actor);
        if (member.getRole() == 0)
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "群主不能直接退出");
        members.delete(
                new LambdaQueryWrapper<UserSession>()
                        .eq(UserSession::getSessionId, request.getSessionId())
                        .eq(UserSession::getUserId, actor));
        List<Long> remaining =
                activeMembers(request.getSessionId()).stream().map(UserSession::getUserId).toList();
        // null 操作者表示主动退出；通知当前仍在群中的成员，与原文一致。
        notifyMembers(request.getSessionId(), 104, remaining, kickBody(List.of(actor), null), null);
        return true;
    }

    @Override
    public PageResponse<GroupMemberDTO> members(Long sessionId, int pageNum, int pageSize) {
        membership(sessionId, CurrentUser.id());
        validatePage(pageNum, pageSize);
        Session session = sessions.selectById(sessionId);
        if (session == null
                || !Integer.valueOf(1).equals(session.getType())
                || !Integer.valueOf(0).equals(session.getStatus()))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不是有效群聊");
        Page<UserSession> page =
                members.selectPage(
                        new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<UserSession>()
                                .eq(UserSession::getSessionId, sessionId)
                                .eq(UserSession::getStatus, 0)
                                .orderByAsc(UserSession::getRole)
                                .orderByAsc(UserSession::getUserId));
        List<Long> ids = page.getRecords().stream().map(UserSession::getUserId).toList();
        Map<Long, User> info = new HashMap<>();
        if (!ids.isEmpty())
            users.selectBatchIds(ids).forEach(user -> info.put(user.getUserId(), user));
        return PageResponse.of(
                page,
                member -> {
                    GroupMemberDTO dto = new GroupMemberDTO();
                    dto.setUserId(member.getUserId());
                    dto.setRole(member.getRole());
                    User user = info.get(member.getUserId());
                    if (user != null) {
                        dto.setNickname(user.getNickname());
                        dto.setAvatar(user.getAvatar());
                    }
                    return dto;
                });
    }

    @Override
    public PageResponse<UserGroupDTO> groups(Long userId, int pageNum, int pageSize) {
        if (!CurrentUser.id().equals(userId)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        validatePage(pageNum, pageSize);
        List<Long> joined =
                members
                        .selectList(
                                new LambdaQueryWrapper<UserSession>()
                                        .eq(UserSession::getUserId, userId)
                                        .eq(UserSession::getStatus, 0))
                        .stream()
                        .map(UserSession::getSessionId)
                        .toList();
        if (joined.isEmpty()) return PageResponse.empty(pageNum, pageSize);
        List<Session> groupList =
                sessions.selectList(
                        new LambdaQueryWrapper<Session>()
                                .in(Session::getSessionId, joined)
                                .eq(Session::getType, 1)
                                .eq(Session::getStatus, 0));
        if (groupList.isEmpty()) return PageResponse.empty(pageNum, pageSize);
        Map<Long, Session> groupMap = new HashMap<>();
        groupList.forEach(group -> groupMap.put(group.getSessionId(), group));
        // 按用户加入时间分页，不能拿群创建顺序代替；同时间用会话 ID 稳定排序。
        Page<UserSession> page =
                members.selectPage(
                        new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<UserSession>()
                                .eq(UserSession::getUserId, userId)
                                .in(UserSession::getSessionId, groupMap.keySet())
                                .eq(UserSession::getStatus, 0)
                                .orderByDesc(UserSession::getCreatedTime)
                                .orderByDesc(UserSession::getSessionId));
        List<Long> pageIds = page.getRecords().stream().map(UserSession::getSessionId).toList();
        Map<Long, Long> owners = new HashMap<>();
        if (!pageIds.isEmpty())
            members.selectList(
                            new LambdaQueryWrapper<UserSession>()
                                    .in(UserSession::getSessionId, pageIds)
                                    .eq(UserSession::getRole, 0)
                                    .eq(UserSession::getStatus, 0))
                    .forEach(owner -> owners.putIfAbsent(owner.getSessionId(), owner.getUserId()));
        return PageResponse.of(
                page,
                membership -> {
                    Session group = groupMap.get(membership.getSessionId());
                    UserGroupDTO dto = new UserGroupDTO();
                    dto.setSessionId(group.getSessionId());
                    dto.setName(group.getName());
                    dto.setSessionName(group.getName());
                    dto.setAvatar(group.getAvatar());
                    dto.setCreatorId(owners.get(group.getSessionId()));
                    int count = activeMembers(group.getSessionId()).size();
                    dto.setMemberCount(count);
                    dto.setMembersCount(count);
                    dto.setRole(membership.getRole());
                    if (membership.getCreatedTime() != null)
                        dto.setCreatedTime(
                                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                        .format(membership.getCreatedTime()));
                    return dto;
                });
    }

    @Override
    public int count(Long sessionId) {
        membership(sessionId, CurrentUser.id());
        return activeMembers(sessionId).size();
    }

    private Session lock(Long id) {
        Session session = sessions.selectForUpdate(id);
        if (session == null
                || !Integer.valueOf(1).equals(session.getType())
                || !Integer.valueOf(0).equals(session.getStatus()))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不是有效群聊");
        return session;
    }

    private User activeUser(Long id) {
        User user = users.selectById(id);
        if (user == null || !Integer.valueOf(0).equals(user.getState()))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不可用");
        return user;
    }

    private List<Long> eligibleFriends(Long actor, List<Long> ids, List<Long> failed) {
        if (ids == null || ids.isEmpty())
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "成员列表不能为空");
        List<Long> valid = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id == null || actor.equals(id)) {
                if (id != null) failed.add(id);
                continue;
            }
            User user = users.selectById(id);
            Long friendship =
                    friends.selectCount(
                            new LambdaQueryWrapper<Friend>()
                                    .eq(Friend::getUserId, actor)
                                    .eq(Friend::getFriendId, id)
                                    .eq(Friend::getStatus, 0));
            if (user != null && Integer.valueOf(0).equals(user.getState()) && friendship > 0)
                valid.add(id);
            else failed.add(id);
        }
        return valid;
    }

    private List<UserSession> activeMembers(Long id) {
        return members.selectList(
                new LambdaQueryWrapper<UserSession>()
                        .eq(UserSession::getSessionId, id)
                        .eq(UserSession::getStatus, 0));
    }

    private UserSession findMember(Long sessionId, Long userId) {
        return members.selectOne(
                new LambdaQueryWrapper<UserSession>()
                        .eq(UserSession::getSessionId, sessionId)
                        .eq(UserSession::getUserId, userId)
                        .eq(UserSession::getStatus, 0));
    }

    private UserSession membership(Long sessionId, Long userId) {
        UserSession member = findMember(sessionId, userId);
        if (member == null) throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "不在该群聊中");
        return member;
    }

    private void add(Long sessionId, Long userId, int role) {
        UserSession member = new UserSession();
        member.setSessionId(sessionId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(0);
        member.setCreatedTime(new Date());
        member.setUpdatedTime(new Date());
        members.insert(member);
    }

    private void validatePage(int num, int size) {
        if (num < 1 || num > 100000 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
    }

    private void notifyMembers(
            Long sessionId,
            int type,
            List<Long> recipients,
            Map<String, Object> body,
            List<Long> failedNotifications) {
        List<Long> targets = new ArrayList<>(new LinkedHashSet<>(recipients));
        List<String> payloads = new ArrayList<>();
        for (Long recipient : targets) {
            SystemNotificationMessage event = new SystemNotificationMessage();
            event.setMessageId("msg_" + SnowflakeUtil.nextId());
            event.setSessionId(sessionId);
            event.setSenderId(null);
            event.setReceiverId(recipient);
            event.setSessionType(1);
            event.setType(type);
            event.setTimestamp(System.currentTimeMillis());
            event.setBody(body);
            payloads.add(
                    JSONUtil.toJsonStr(
                            event, cn.hutool.json.JSONConfig.create().setIgnoreNullValue(false)));
        }
        Runnable send =
                () -> {
                    for (int i = 0; i < payloads.size(); i++) {
                        Long recipient = targets.get(i);
                        try {
                            var result =
                                    kafka.send(
                                            CommonConstant.KAFKA_SYSTEM_NOTIFICATION_TOPIC,
                                            recipient.toString(),
                                            payloads.get(i));
                            if (failedNotifications != null)
                                result.get(5, java.util.concurrent.TimeUnit.SECONDS);
                            else
                                result.whenComplete(
                                        (ok, error) -> {
                                            if (error != null)
                                                log.error(
                                                        "群组通知投递失败，sessionId={}，接收者={}",
                                                        sessionId,
                                                        recipient,
                                                        error);
                                        });
                        } catch (Exception error) {
                            if (error instanceof InterruptedException)
                                Thread.currentThread().interrupt();
                            if (failedNotifications != null
                                    && !failedNotifications.contains(recipient))
                                failedNotifications.add(recipient);
                            // 此时数据库已提交；通知失败可返回失败成员，但不能伪装成数据库回滚。
                            log.error(
                                    "群组已提交，通知未确认，sessionId={}，接收者={}", sessionId, recipient, error);
                        }
                    }
                };
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            send.run();
                        }
                    });
        else send.run();
    }

    private static final String DEFAULT_GROUP_AVATAR_URL = "/assets/group-avatar.png";

    private String generateGroupName(List<Long> memberIds) {
        Map<Long, User> byId = new HashMap<>();
        users.selectBatchIds(memberIds).forEach(user -> byId.put(user.getUserId(), user));
        StringBuilder name = new StringBuilder();
        for (Long id : memberIds) {
            User user = byId.get(id);
            if (user == null) continue;
            if (!name.isEmpty()) name.append("、");
            name.append(user.getNickname());
            if (name.length() >= 16) {
                name.setLength(16);
                break;
            }
        }
        return name.toString();
    }

    private Map<String, Object> newGroupBody(Session session, Long creator, int count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionName", session.getName());
        body.put(
                "avatar",
                session.getAvatar() == null ? DEFAULT_GROUP_AVATAR_URL : session.getAvatar());
        body.put("creatorId", creator);
        body.put("membersCount", count);
        return body;
    }

    private Map<String, Object> kickBody(List<Long> ids, Long operator) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberIds", ids);
        body.put("operatorId", operator);
        return body;
    }
}

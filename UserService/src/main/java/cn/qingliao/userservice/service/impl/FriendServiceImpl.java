package cn.qingliao.userservice.service.impl;

import cn.qingliao.common.common.*;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.constant.SessionTypeConstant;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.exception.ThrowUtils;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.common.model.dto.PageRequest;
import cn.qingliao.common.utils.SnowflakeUtil;
import cn.qingliao.userservice.constant.*;
import cn.qingliao.userservice.constant.FriendStatusEnum;
import cn.qingliao.userservice.constant.UserConstant;
import cn.qingliao.userservice.constant.UserStateEnum;
import cn.qingliao.userservice.mapper.*;
import cn.qingliao.userservice.mapper.ApplyFriendMapper;
import cn.qingliao.userservice.mapper.FriendMapper;
import cn.qingliao.userservice.mapper.SessionMapper;
import cn.qingliao.userservice.mapper.UserSessionMapper;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.FriendDTO;
import cn.qingliao.userservice.model.dto.ModifyFriendApplicationResponse;
import cn.qingliao.userservice.model.dto.NewSessionNotificationDTO;
import cn.qingliao.userservice.model.dto.request.*;
import cn.qingliao.userservice.model.entity.*;
import cn.qingliao.userservice.model.entity.ApplyFriend;
import cn.qingliao.userservice.model.entity.Friend;
import cn.qingliao.userservice.model.entity.Session;
import cn.qingliao.userservice.model.entity.User;
import cn.qingliao.userservice.model.entity.UserSession;
import cn.qingliao.userservice.model.vo.*;
import cn.qingliao.userservice.model.vo.FriendDetailVO;
import cn.qingliao.userservice.service.*;
import cn.qingliao.userservice.service.FriendService;
import cn.qingliao.userservice.service.NotificationService;
import cn.qingliao.userservice.service.SessionService;
import cn.qingliao.userservice.service.UserService;
import cn.qingliao.userservice.service.UserSessionService;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@lombok.RequiredArgsConstructor
@Slf4j
@Service
public class FriendServiceImpl extends ServiceImpl<FriendMapper, Friend> implements FriendService {
    private final UserService userService;

    private final SessionMapper sessionMapper;

    private final UserSessionMapper userSessionMapper;

    @Override
    public FriendDetailVO searchUserByKeyword(String userId, String keyword) {
        ThrowUtils.throwIf(!StringUtils.hasText(keyword), ErrorCode.PARAMS_ERROR, "搜索关键字不能为空");
        // 根据正则表达式自动判断关键字类型并构建查询条件
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        if (keyword.matches(UserConstant.PHONE_REGEX)) {
            queryWrapper.eq(User::getPhone, keyword);
        } else if (keyword.matches(UserConstant.EMAIL_REGEX)) {
            queryWrapper.eq(User::getEmail, keyword);
        } else {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请输入有效的手机号或邮箱");
        }
        User user = userService.getOne(queryWrapper);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        // 获取用户详情
        return getFriendDetails(userId, String.valueOf(user.getUserId()));
    }

    @Override
    public FriendDetailVO getFriendDetails(String userId, String friendId) {
        Long userId1 = parseUserId(userId);
        Long friendId1 = parseUserId(friendId);
        // 1. 获取好友用户信息
        User friendUser = userService.getById(friendId1);
        validateFriendUser(friendUser);
        // 2. 构建好友详情VO
        FriendDetailVO friendDetailVO = buildFriendDetailVO(friendUser);
        // 3. 填充会话ID
        populateSessionId(userId1, friendId1, friendDetailVO);
        // 4. 填充好友状态
        populateFriendStatus(userId1, friendId1, friendDetailVO);
        return friendDetailVO;
    }

    private Long parseUserId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户ID格式错误");
        }
    }

    private void validateFriendUser(User friendUser) {
        ThrowUtils.throwIf(friendUser == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        // 用户状态：0 正常，1 封禁，2 注销
        ThrowUtils.throwIf(
                friendUser.getState() == UserStateEnum.BANNED.getCode(),
                ErrorCode.FORBIDDEN_ERROR,
                "该用户已被封禁");
        ThrowUtils.throwIf(
                friendUser.getState() == UserStateEnum.CANCELLED.getCode(),
                ErrorCode.NOT_FOUND_ERROR,
                "该用户已注销");
    }

    private FriendDetailVO buildFriendDetailVO(User friendUser) {
        FriendDetailVO vo = new FriendDetailVO();
        vo.setUserId(String.valueOf(friendUser.getUserId()));
        vo.setNickname(friendUser.getNickname());
        vo.setAvatar(friendUser.getAvatar());
        vo.setEmail(friendUser.getEmail());
        vo.setPhone(friendUser.getPhone());
        vo.setSignature(friendUser.getDescription());
        vo.setGender(friendUser.getGender());
        return vo;
    }

    private void populateSessionId(Long userId, Long friendId, FriendDetailVO friendDetailVO) {
        // 1. 查找两个用户共同的单聊会话
        LambdaQueryWrapper<UserSession> userSession1Wrapper = new LambdaQueryWrapper<>();
        userSession1Wrapper.eq(UserSession::getUserId, userId);
        List<UserSession> userSessions1 = userSessionMapper.selectList(userSession1Wrapper);
        LambdaQueryWrapper<UserSession> userSession2Wrapper = new LambdaQueryWrapper<>();
        userSession2Wrapper.eq(UserSession::getUserId, friendId);
        List<UserSession> userSessions2 = userSessionMapper.selectList(userSession2Wrapper);
        // 2. 找出共同的会话ID
        List<Long> sessionIds1 =
                userSessions1.stream().map(UserSession::getSessionId).collect(Collectors.toList());
        List<Long> sessionIds2 =
                userSessions2.stream().map(UserSession::getSessionId).collect(Collectors.toList());
        sessionIds1.retainAll(sessionIds2); // 保留sessionIds1中sessionIds2的交集
        if (!sessionIds1.isEmpty()) {
            // 3. 筛选出单聊会话
            LambdaQueryWrapper<Session> sessionWrapper = new LambdaQueryWrapper<>();
            sessionWrapper
                    .in(Session::getSessionId, sessionIds1)
                    .eq(Session::getType, SessionTypeConstant.SIGNAL_TYPE);
            List<Session> sessions = sessionMapper.selectList(sessionWrapper);
            if (!sessions.isEmpty()) {
                friendDetailVO.setSessionId(String.valueOf(sessions.get(0).getSessionId()));
            } else {
                friendDetailVO.setSessionId(null);
            }
        } else {
            friendDetailVO.setSessionId(null);
        }
    }

    private void populateFriendStatus(Long userId, Long friendId, FriendDetailVO friendDetailVO) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId);
        Friend friend = this.getOne(wrapper);
        if (friend != null) {
            friendDetailVO.setStatus(friend.getStatus());
        } else {
            friendDetailVO.setStatus(FriendStatusEnum.NON_FRIEND.getCode());
        }
    }

    private final FriendMapper friendMapper;

    @Override
    public IPage<FriendDTO> getFriends(String userId1, PageRequest pageRequest, String key) {
        Long userId = parseUserId(userId1);
        validateUserId(userId);
        int pageNum = pageRequest.getPageNum();
        int pageSize = pageRequest.getPageSize();
        // 1. 查询好友关系列表（使用Lambda Wrapper）
        LambdaQueryWrapper<Friend> friendWrapper = new LambdaQueryWrapper<>();
        friendWrapper
                .eq(Friend::getUserId, userId)
                .ne(Friend::getStatus, FriendStatusEnum.DELETED.getCode())
                .orderByDesc(Friend::getCreatedTime); // 按创建时间降序排列
        List<Friend> friendList = friendMapper.selectList(friendWrapper);
        // 2. 获取好友ID列表，从好友列表中提取所有好友的ID，组成一个新的ID列表。
        List<Long> friendIds =
                friendList.stream() // 将好友列表转换成流
                        .map(Friend::getFriendId) // 对每个好友对象，提取其 friendId（映射转换）
                        .collect(Collectors.toList()); // 将所有ID收集成一个 List<Long> 类型的列表
        if (friendIds.isEmpty()) {
            // 返回空分页结果
            Page<FriendDTO> emptyPage = new Page<>(pageNum, pageSize);
            emptyPage.setTotal(0);
            emptyPage.setRecords(List.of());
            return emptyPage;
        }
        // 3. 查询好友用户信息
        LambdaQueryWrapper<User> userWrapper = new LambdaQueryWrapper<>();
        userWrapper.in(User::getUserId, friendIds);
        // 如果有搜索关键字，添加搜索条件
        if (StringUtils.hasText(key)) {
            userWrapper.and(
                    wrapper ->
                            wrapper.like(User::getNickname, key)
                                    .or()
                                    .like(User::getPhone, key)
                                    .or()
                                    .like(User::getUserId, key));
        }
        List<User> users = userService.list(userWrapper); // 好友信息列表
        // 4. 查询好友与当前用户之间的会话ID映射（批量查询优化）
        Map<Long, String> friendSessionMap = buildFriendSessionMap(userId, friendIds);
        // 5. 构建好友ID到好友关系的映射（避免嵌套stream，O(n+m)），n = friendList 的大小（好友关系数量），m = users
        // 的大小（查询到的好友用户信息数量）
        Map<Long, Friend> friendRelationMap =
                friendList.stream()
                        .collect(
                                Collectors.toMap(
                                        Friend::getFriendId,
                                        f -> f)); // 创建一个 Map，将好友关系列表转换为 Map，好友ID为key、好友对象为value。
        // 6. 构建 FriendDTO 列表
        List<FriendDTO> friendDTOList =
                users.stream()
                        .map(
                                user -> {
                                    FriendDTO dto = new FriendDTO();
                                    dto.setUserId(
                                            String.valueOf(user.getUserId())); // 设置dto的ID（好友用户ID）
                                    dto.setNickname(user.getNickname());
                                    dto.setAvatar(user.getAvatar());
                                    dto.setSignature(user.getDescription());
                                    Friend friendRelation =
                                            friendRelationMap.get(
                                                    user.getUserId()); // 通过用户ID从映射表中获取对应的好友关系
                                    dto.setStatus(
                                            friendRelation != null
                                                    ? friendRelation.getStatus()
                                                    : FriendStatusEnum.NON_FRIEND
                                                            .getCode()); // 如果存在好友关系则取其状态，否则标记为非好友
                                    dto.setSessionId(
                                            friendSessionMap.get(
                                                    user.getUserId())); // 从会话映射中获取该用户与当前用户的聊天会话ID
                                    return dto;
                                })
                        .collect(Collectors.toList());
        // 7. 手动分页
        // 创建分页对象：初始化页码和每页大小
        Page<FriendDTO> page = new Page<>(pageNum, pageSize);
        page.setTotal(friendDTOList.size());
        // 计算索引范围：根据页码计算起始和结束位置，防止越界
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, friendDTOList.size());
        // 截取数据：使用 subList 提取当前页数据，若超出范围则返回空列表
        if (fromIndex < friendDTOList.size()) {
            page.setRecords(friendDTOList.subList(fromIndex, toIndex));
        } else {
            page.setRecords(List.of());
        }
        return page;
    }

    private java.util.Map<Long, String> buildFriendSessionMap(Long userId, List<Long> friendIds) {
        java.util.Map<Long, String> friendSessionMap = new java.util.HashMap<>();
        if (friendIds.isEmpty()) {
            return friendSessionMap;
        }
        // 1. 查询当前用户的所有 UserSession
        LambdaQueryWrapper<UserSession> currentUserSessionWrapper = new LambdaQueryWrapper<>();
        currentUserSessionWrapper.eq(UserSession::getUserId, userId);
        List<UserSession> currentUserSessions =
                userSessionMapper.selectList(currentUserSessionWrapper);
        if (currentUserSessions.isEmpty()) {
            return friendSessionMap;
        }
        // 2. 获取当前用户的所有会话ID
        List<Long> currentUserSessionIds =
                currentUserSessions.stream()
                        .map(UserSession::getSessionId)
                        .collect(Collectors.toList());
        // 3. 查询这些会话的详细信息，筛选出单聊会话（SIGNAL_TYPE）
        LambdaQueryWrapper<Session> sessionWrapper = new LambdaQueryWrapper<>();
        sessionWrapper
                .in(Session::getSessionId, currentUserSessionIds)
                .eq(Session::getType, SessionTypeConstant.SIGNAL_TYPE);
        List<Session> singleChatSessions = sessionMapper.selectList(sessionWrapper);
        if (singleChatSessions.isEmpty()) {
            return friendSessionMap;
        }
        // 4. 获取单聊会话ID列表
        List<Long> singleChatSessionIds =
                singleChatSessions.stream().map(Session::getSessionId).collect(Collectors.toList());
        // 5. 查询所有好友在这些会话中的 UserSession 记录
        LambdaQueryWrapper<UserSession> friendSessionWrapper = new LambdaQueryWrapper<>();
        friendSessionWrapper
                .in(UserSession::getUserId, friendIds)
                .in(UserSession::getSessionId, singleChatSessionIds);
        List<UserSession> friendUserSessions = userSessionMapper.selectList(friendSessionWrapper);
        // 6. 构建 friendId -> sessionId 映射
        for (UserSession friendUserSession : friendUserSessions) {
            Long friendId = friendUserSession.getUserId();
            Long sessionId = friendUserSession.getSessionId();
            // 验证这个会话是否确实是当前用户和该好友的共同会话
            if (currentUserSessionIds.contains(sessionId)) {
                friendSessionMap.put(friendId, String.valueOf(sessionId));
            }
        }
        return friendSessionMap;
    }

    private void validateUserId(Long userId) {
        ThrowUtils.throwIf(userId == null || userId < 0, ErrorCode.PARAMS_ERROR, "用户ID无效");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteFriend(Long userId, Long friendId) {
        validateUserId(userId);
        validateUserId(friendId);
        try {
            // 1. 删除好友申请记录
            deleteApplyFriendRecords(userId, friendId);
            // 2. 删除好友关系记录
            deleteFriendRecords(userId, friendId);
            // 3. 删除会话记录
            deleteSessionRecords(userId, friendId);
            // 4. 删除双向好友关系缓存
            evictFriendCache(userId, friendId);
            return true;
        } catch (Exception e) {
            log.error("删除好友失败，用户ID：{}，好友ID：{}，原因：{}", userId, friendId, e.getMessage(), e);
            throw new RuntimeException("删除好友失败");
        }
    }

    private final ApplyFriendMapper applyFriendMapper;

    private void deleteApplyFriendRecords(Long userId, Long friendId) {
        LambdaQueryWrapper<ApplyFriend> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(
                w ->
                        w.nested(
                                        nested ->
                                                nested.eq(ApplyFriend::getSenderId, userId)
                                                        .eq(ApplyFriend::getReceiverId, friendId))
                                .or()
                                .nested(
                                        nested ->
                                                nested.eq(ApplyFriend::getSenderId, friendId)
                                                        .eq(ApplyFriend::getReceiverId, userId)));
        applyFriendMapper.delete(wrapper);
    }

    private void deleteFriendRecords(Long userId, Long friendId) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(
                w ->
                        w.nested(
                                        nested ->
                                                nested.eq(Friend::getUserId, userId)
                                                        .eq(Friend::getFriendId, friendId))
                                .or()
                                .nested(
                                        nested ->
                                                nested.eq(Friend::getUserId, friendId)
                                                        .eq(Friend::getFriendId, userId)));
        friendMapper.delete(wrapper);
    }

    private void deleteSessionRecords(Long userId, Long friendId) {
        // 1. 查找两个用户共同的单聊会话
        LambdaQueryWrapper<UserSession> userSession1Wrapper = new LambdaQueryWrapper<>();
        userSession1Wrapper.eq(UserSession::getUserId, userId);
        List<UserSession> userSessions1 = userSessionMapper.selectList(userSession1Wrapper);
        LambdaQueryWrapper<UserSession> userSession2Wrapper = new LambdaQueryWrapper<>();
        userSession2Wrapper.eq(UserSession::getUserId, friendId);
        List<UserSession> userSessions2 = userSessionMapper.selectList(userSession2Wrapper);
        // 2. 找出共同的会话ID
        List<Long> sessionIds1 =
                userSessions1.stream().map(UserSession::getSessionId).collect(Collectors.toList());
        List<Long> sessionIds2 =
                userSessions2.stream().map(UserSession::getSessionId).collect(Collectors.toList());
        sessionIds1.retainAll(sessionIds2);
        List<Long> commonSessionIds = sessionIds1;
        // 3. 筛选出单聊会话
        if (!commonSessionIds.isEmpty()) {
            LambdaQueryWrapper<Session> sessionWrapper = new LambdaQueryWrapper<>();
            sessionWrapper
                    .in(Session::getSessionId, commonSessionIds)
                    .eq(Session::getType, SessionTypeConstant.SIGNAL_TYPE);
            List<Session> sessions = sessionMapper.selectList(sessionWrapper);
            List<Long> singleChatSessionIds =
                    sessions.stream().map(Session::getSessionId).collect(Collectors.toList());
            if (!singleChatSessionIds.isEmpty()) {
                // 4. 删除用户会话关系
                LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
                wrapper.in(UserSession::getSessionId, singleChatSessionIds);
                userSessionMapper.delete(wrapper);
                // 5. 删除会话
                LambdaQueryWrapper<Session> sessionDeleteWrapper = new LambdaQueryWrapper<>();
                sessionDeleteWrapper.in(Session::getSessionId, singleChatSessionIds);
                sessionMapper.delete(sessionDeleteWrapper);
            }
        }
    }

    private final StringRedisTemplate stringRedisTemplate;

    private static final String FRIEND_STATUS_KEY_PREFIX = "msg:validate:friend:status:";

    private void evictFriendCache(Long userId, Long friendId) {
        try {
            String key1 = FRIEND_STATUS_KEY_PREFIX + userId + ":" + friendId;
            String key2 = FRIEND_STATUS_KEY_PREFIX + friendId + ":" + userId;
            stringRedisTemplate.delete(Arrays.asList(key1, key2));
            log.info("已清除好友关系缓存: {} <-> {}", userId, friendId);
        } catch (Exception e) {
            // 缓存清除失败不影响主流程，记录日志即可
            log.warn("清除好友关系缓存失败: {} <-> {}, 原因: {}", userId, friendId, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean blockFriend(Long userId, Long friendId) {
        validateUserId(userId);
        validateUserId(friendId);
        // 1. 检查好友关系是否存在
        LambdaQueryWrapper<Friend> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId);
        Friend friend = this.getOne(queryWrapper);
        ThrowUtils.throwIf(friend == null, ErrorCode.NOT_FOUND_ERROR, "好友关系不存在");
        // 2. 使用Lambda Wrapper更新好友状态为拉黑（Friend表使用复合主键，不能使用updateById）
        LambdaUpdateWrapper<Friend> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper
                .set(Friend::getStatus, FriendStatusEnum.BLOCKED.getCode())
                .set(Friend::getUpdatedTime, LocalDateTime.now())
                .eq(Friend::getUserId, userId)
                .eq(Friend::getFriendId, friendId);
        boolean result = this.update(updateWrapper);
        // 3. 删除双向好友关系缓存
        evictFriendCache(userId, friendId);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unblockFriend(Long userId, Long friendId) {
        validateUserId(userId);
        validateUserId(friendId);
        // 1. 检查好友关系是否存在
        LambdaQueryWrapper<Friend> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId);
        Friend friend = this.getOne(queryWrapper);
        ThrowUtils.throwIf(friend == null, ErrorCode.NOT_FOUND_ERROR, "好友关系不存在");
        ThrowUtils.throwIf(
                friend.getStatus() != FriendStatusEnum.BLOCKED.getCode(),
                ErrorCode.OPERATION_ERROR,
                "该好友未被拉黑");
        // 2. 使用Lambda Wrapper更新好友状态为正常（Friend表使用复合主键，不能使用updateById）
        LambdaUpdateWrapper<Friend> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper
                .set(Friend::getStatus, FriendStatusEnum.NORMAL.getCode())
                .set(Friend::getUpdatedTime, LocalDateTime.now())
                .eq(Friend::getUserId, userId)
                .eq(Friend::getFriendId, friendId);
        boolean result = this.update(updateWrapper);
        // 3. 删除双向好友关系缓存
        evictFriendCache(userId, friendId);
        return result;
    }

    private final SessionService sessionService;

    private final NotificationService notificationService;

    private final UserSessionService userSessionService;

    private static final int USER_ROLE_NORMAL = 2;

    private static final int SESSION_STATUS_NORMAL = 0;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModifyFriendApplicationResponse addFriend(User recipient, Long friendId) {
        validateUserId(friendId);
        ThrowUtils.throwIf(recipient == null, ErrorCode.NOT_FOUND_ERROR, "接收者用户不存在");
        // 1. 验证申请者用户是否存在
        User applicant = userService.getById(friendId);
        ThrowUtils.throwIf(applicant == null, ErrorCode.NOT_FOUND_ERROR, "好友申请者不存在");
        // 2. 检查是否已是好友关系（使用Lambda Wrapper）
        boolean exists =
                this.lambdaQuery()
                        .eq(Friend::getUserId, recipient.getUserId())
                        .eq(Friend::getFriendId, friendId)
                        .exists();
        ThrowUtils.throwIf(exists, ErrorCode.OPERATION_ERROR, "已经是好友关系");
        // 3. 创建双向好友关系
        createFriendRelations(recipient.getUserId(), friendId);
        // 4. 创建会话
        Long sessionId = createSession();
        // 5. 创建用户会话关系
        createUserSessions(recipient.getUserId(), friendId, sessionId);
        // 6. 发送Kafka通知给申请者
        sendNewSessionNotification(friendId, recipient, sessionId);
        // 7. 构建响应对象
        return buildModifyFriendApplicationResponse(applicant, sessionId);
    }

    private void createFriendRelations(Long userId, Long friendId) {
        // 1. 创建第一条好友关系
        Friend friend1 = new Friend();
        friend1.setUserId(userId);
        friend1.setFriendId(friendId);
        friend1.setStatus(FriendStatusEnum.NORMAL.getCode());
        friend1.setCreatedTime(LocalDateTime.now());
        friend1.setUpdatedTime(LocalDateTime.now());
        // 2. 创建第二条好友关系
        Friend friend2 = new Friend();
        friend2.setUserId(friendId);
        friend2.setFriendId(userId);
        friend2.setStatus(FriendStatusEnum.NORMAL.getCode());
        friend2.setCreatedTime(LocalDateTime.now());
        friend2.setUpdatedTime(LocalDateTime.now());
        // 3. 使用Mapper的insert方法插入（Friend表使用复合主键，不能使用save()）
        int inserted1 = friendMapper.insert(friend1);
        int inserted2 = friendMapper.insert(friend2);
        ThrowUtils.throwIf(inserted1 <= 0 || inserted2 <= 0, ErrorCode.SYSTEM_ERROR, "添加好友关系失败");
    }

    private Long createSession() {
        Long sessionId = SnowflakeUtil.nextId();
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setName("");
        session.setType(SessionTypeConstant.SIGNAL_TYPE);
        session.setStatus(SESSION_STATUS_NORMAL);
        session.setCreatedTime(new Date());
        session.setUpdatedTime(new Date());
        boolean sessionSaved = sessionService.save(session);
        ThrowUtils.throwIf(!sessionSaved, ErrorCode.SYSTEM_ERROR, "创建会话失败");
        return sessionId;
    }

    private void createUserSessions(Long userId, Long friendId, Long sessionId) {
        // 1. 创建第一条用户会话关系
        UserSession userSession1 = new UserSession();
        userSession1.setUserId(userId);
        userSession1.setSessionId(sessionId);
        userSession1.setRole(USER_ROLE_NORMAL);
        userSession1.setStatus(SESSION_STATUS_NORMAL);
        userSession1.setCreatedTime(new Date());
        userSession1.setUpdatedTime(new Date());
        // 2. 创建第二条用户会话关系
        UserSession userSession2 = new UserSession();
        userSession2.setUserId(friendId);
        userSession2.setSessionId(sessionId);
        userSession2.setRole(USER_ROLE_NORMAL);
        userSession2.setStatus(SESSION_STATUS_NORMAL);
        userSession2.setCreatedTime(new Date());
        userSession2.setUpdatedTime(new Date());
        boolean userSessionSaved1 = userSessionService.save(userSession1);
        boolean userSessionSaved2 = userSessionService.save(userSession2);
        ThrowUtils.throwIf(
                !userSessionSaved1 || !userSessionSaved2, ErrorCode.SYSTEM_ERROR, "创建用户会话关系失败");
    }

    private void sendNewSessionNotification(Long recipientId, User sender, Long sessionId) {
        try {
            NewSessionNotificationDTO notification = new NewSessionNotificationDTO();
            notification.setSessionName(sender.getNickname());
            notification.setAvatar(sender.getAvatar());
            notificationService.pushNewSession(
                    sender.getUserId(),
                    recipientId,
                    sessionId,
                    SessionTypeConstant.SIGNAL_TYPE,
                    notification);
            log.info("发送新会话通知成功，接收者ID：{}，会话ID：{}", recipientId, sessionId);
        } catch (Exception e) {
            log.warn("发送新会话通知失败，接收者ID：{}，会话ID：{}，原因：{}", recipientId, sessionId, e.getMessage());
        }
    }

    private ModifyFriendApplicationResponse buildModifyFriendApplicationResponse(
            User applicant, Long sessionId) {
        ModifyFriendApplicationResponse response = new ModifyFriendApplicationResponse();
        response.setUserId(String.valueOf(applicant.getUserId()));
        response.setSessionId(String.valueOf(sessionId));
        response.setSessionType(SessionTypeConstant.SIGNAL_TYPE);
        response.setSessionName(applicant.getNickname());
        response.setAvatar(applicant.getAvatar());
        return response;
    }
}

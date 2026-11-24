package cn.qingliao.userservice.controller;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.common.ResultUtils;
import cn.qingliao.common.model.dto.validation.*;
import cn.qingliao.common.model.dto.validation.GroupMembershipResponse;
import cn.qingliao.common.model.dto.validation.MessageValidateResponse;
import cn.qingliao.common.model.dto.validation.SingleMessageValidateRequest;
import cn.qingliao.common.model.dto.validation.UserStatusResponse;
import cn.qingliao.common.model.vo.UserInfosResponse;
import cn.qingliao.userservice.service.InternalValidationService;
import cn.qingliao.userservice.service.UserService;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/internal")
public class InternalValidationController {
    @Resource private InternalValidationService internalValidationService;
    @Resource private UserService userService;

    @GetMapping("/user/status")
    public BaseResponse<UserStatusResponse> getUserStatus(@RequestParam Long userId) {
        log.debug("内部接口: 查询用户状态, userId={}", userId);
        UserStatusResponse response = internalValidationService.getUserStatus(userId);
        return ResultUtils.success(response);
    }

    @GetMapping("/group/memberCount")
    public BaseResponse<GroupMemberCountResponse> getGroupMemberCount(
            @RequestParam Long sessionId) {
        log.debug("内部接口: 获取群成员数量, sessionId={}", sessionId);
        GroupMemberCountResponse response =
                internalValidationService.getGroupMemberCount(sessionId);
        return ResultUtils.success(response);
    }

    @GetMapping("/group/isMember")
    public BaseResponse<GroupMembershipResponse> checkGroupMembership(
            @RequestParam Long userId, @RequestParam Long sessionId) {
        log.debug("内部接口: 验证群成员资格, userId={}, sessionId={}", userId, sessionId);
        GroupMembershipResponse response =
                internalValidationService.checkGroupMembership(userId, sessionId);
        return ResultUtils.success(response);
    }

    @PostMapping("/validation/single-message")
    public BaseResponse<MessageValidateResponse> validateSingleMessage(
            @RequestBody SingleMessageValidateRequest request) {
        log.debug(
                "单聊消息校验请求: senderId={}, receiverId={}, sessionId={}",
                request.getSenderId(),
                request.getReceiverId(),
                request.getSessionId());
        MessageValidateResponse response = internalValidationService.validateSingleMessage(request);
        return ResultUtils.success(response);
    }

    @GetMapping("/users/batch")
    public BaseResponse<?> batchGetUserInfos(@RequestParam("userIds") List<Long> userIds) {
        log.debug("内部接口: 批量获取用户信息, userIds={}", userIds);
        // 参数校验
        if (userIds == null || userIds.isEmpty()) {
            return ResultUtils.success(new HashMap<>());
        }
        // 限制单次查询数量，防止滥用
        if (userIds.size() > 100) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "单次查询用户数量不能超过100");
        }
        Map<Long, UserInfosResponse> result = userService.getUserInfos(userIds);
        return ResultUtils.success(result);
    }
}

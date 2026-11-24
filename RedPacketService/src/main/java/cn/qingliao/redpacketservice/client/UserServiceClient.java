package cn.qingliao.redpacketservice.client;

import cn.qingliao.common.common.*;
import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.common.model.dto.validation.*;
import cn.qingliao.common.model.dto.validation.GroupMemberCountResponse;
import cn.qingliao.common.model.dto.validation.GroupMembershipResponse;
import cn.qingliao.common.model.dto.validation.MessageValidateResponse;
import cn.qingliao.common.model.dto.validation.SingleMessageValidateRequest;
import cn.qingliao.common.model.dto.validation.UserStatusResponse;
import cn.qingliao.common.model.vo.UserInfosResponse;
import cn.qingliao.redpacketservice.constant.*;
import cn.qingliao.redpacketservice.mapper.*;
import cn.qingliao.redpacketservice.model.dto.*;
import cn.qingliao.redpacketservice.model.entity.*;
import cn.qingliao.redpacketservice.model.vo.*;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.*;
import java.util.*;
import java.util.List;
import java.util.Map;

@FeignClient(
        name = "UserService",
        url = "${services.userservice.url:http://localhost:8104}",
        fallbackFactory = UserServiceClientFallbackFactory.class)
public interface UserServiceClient {

    @GetMapping("/api/internal/user/status")
    BaseResponse<UserStatusResponse> getUserStatus(@RequestParam("userId") Long userId);

    @GetMapping("/api/internal/group/memberCount")
    BaseResponse<GroupMemberCountResponse> getGroupMemberCount(
            @RequestParam("sessionId") Long sessionId);

    @GetMapping("/api/internal/group/isMember")
    BaseResponse<GroupMembershipResponse> checkGroupMembership(
            @RequestParam("userId") Long userId, @RequestParam("sessionId") Long sessionId);

    @PostMapping("/api/internal/validation/single-message")
    BaseResponse<MessageValidateResponse> validateSingleMessage(
            @RequestBody SingleMessageValidateRequest request);

    @GetMapping("/api/internal/users/batch")
    BaseResponse<Map<Long, UserInfosResponse>> batchGetUserInfos(
            @RequestParam("userIds") List<Long> userIds);

    @GetMapping("/api/user/internal/receivers")
    List<Long> getUserIdBySessionId(@RequestParam("sessionId") Long sessionId);
}

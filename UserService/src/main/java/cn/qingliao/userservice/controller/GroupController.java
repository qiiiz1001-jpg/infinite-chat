package cn.qingliao.userservice.controller;

import cn.qingliao.common.common.*;
import cn.qingliao.common.model.dto.PageResponse;
import cn.qingliao.common.security.RequireActor;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.GroupMemberDTO;
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
import cn.qingliao.userservice.service.GroupService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/group")
@RequireActor
@RequiredArgsConstructor
public class GroupController {
    private final GroupService groups;

    @PostMapping
    public BaseResponse<CreateGroupResponse> create(@RequestBody CreateGroupRequest request) {
        return ResultUtils.success(groups.createGroup(request));
    }

    @PostMapping("/invite")
    public BaseResponse<InviteGroupResponse> invite(@RequestBody InviteGroupRequest request) {
        return ResultUtils.success(groups.inviteGroup(request));
    }

    @PostMapping("/kick")
    public BaseResponse<KickGroupMembersResponse> kick(
            @RequestBody KickGroupMembersRequest request) {
        return ResultUtils.success(groups.kick(request));
    }

    @PostMapping("/exit")
    public BaseResponse<Boolean> exit(@RequestBody ExitGroupRequest request) {
        return ResultUtils.success(groups.exit(request));
    }

    @GetMapping("/{sessionId}/members")
    public BaseResponse<PageResponse<GroupMemberDTO>> members(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResultUtils.success(groups.members(sessionId, pageNum, pageSize));
    }

    @GetMapping("/user/{userId}")
    public BaseResponse<PageResponse<UserGroupDTO>> joined(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResultUtils.success(groups.groups(userId, pageNum, pageSize));
    }

    @GetMapping("/{sessionId}/count")
    public BaseResponse<GroupMemberCountResponse> count(@PathVariable Long sessionId) {
        GroupMemberCountResponse response = new GroupMemberCountResponse();
        response.setMembersCount(groups.count(sessionId));
        return ResultUtils.success(response);
    }
}

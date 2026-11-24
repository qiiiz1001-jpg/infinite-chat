package cn.qingliao.userservice.service;

import cn.qingliao.common.model.dto.PageResponse;
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

public interface GroupService {
    CreateGroupResponse createGroup(CreateGroupRequest request);

    InviteGroupResponse inviteGroup(InviteGroupRequest request);

    KickGroupMembersResponse kick(KickGroupMembersRequest request);

    boolean exit(ExitGroupRequest request);

    PageResponse<GroupMemberDTO> members(Long sessionId, int pageNum, int pageSize);

    PageResponse<UserGroupDTO> groups(Long userId, int pageNum, int pageSize);

    int count(Long sessionId);
}

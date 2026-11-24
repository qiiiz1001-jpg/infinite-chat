package cn.qingliao.userservice.service;

import cn.qingliao.common.model.dto.validation.*;
import cn.qingliao.common.model.dto.validation.GroupMembershipResponse;
import cn.qingliao.common.model.dto.validation.MessageValidateResponse;
import cn.qingliao.common.model.dto.validation.SingleMessageValidateRequest;
import cn.qingliao.common.model.dto.validation.UserStatusResponse;

import java.util.*;

public interface InternalValidationService {
    UserStatusResponse getUserStatus(Long userId);

    GroupMemberCountResponse getGroupMemberCount(Long sessionId);

    GroupMembershipResponse checkGroupMembership(Long userId, Long sessionId);

    MessageValidateResponse validateSingleMessage(SingleMessageValidateRequest request);
}

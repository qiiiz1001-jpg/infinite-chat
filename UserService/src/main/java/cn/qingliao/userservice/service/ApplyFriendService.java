package cn.qingliao.userservice.service;

import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.common.model.dto.PageRequest;
import cn.qingliao.userservice.constant.*;
import cn.qingliao.userservice.mapper.*;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.ApplyFriendDTO;
import cn.qingliao.userservice.model.dto.ModifyFriendApplicationResponse;
import cn.qingliao.userservice.model.dto.request.*;
import cn.qingliao.userservice.model.entity.*;
import cn.qingliao.userservice.model.entity.ApplyFriend;
import cn.qingliao.userservice.model.vo.*;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;

import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

public interface ApplyFriendService extends IService<ApplyFriend> {

    Long sendFriendRequest(Long senderId, Long receiverId, String message);

    IPage<ApplyFriendDTO> getReceivedRequestsWithUserInfo(Long userId, PageRequest pageRequest);

    int getUnreadCount(Long userId);

    ModifyFriendApplicationResponse modifyApplicationStatus(
            Long receiverId, java.util.List<Long> senderIds, Integer status);
}

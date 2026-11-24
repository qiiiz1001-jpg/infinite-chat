package cn.qingliao.userservice.service;

import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.userservice.constant.*;
import cn.qingliao.userservice.mapper.*;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.FriendApplicationNotificationDTO;
import cn.qingliao.userservice.model.dto.NewSessionNotificationDTO;
import cn.qingliao.userservice.model.dto.request.*;
import cn.qingliao.userservice.model.entity.*;
import cn.qingliao.userservice.model.vo.*;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

public interface NotificationService {

    void pushNewApply(Long userId, FriendApplicationNotificationDTO notification);

    void pushNewSession(
            Long senderId,
            Long userId,
            Long sessionId,
            Integer sessionType,
            NewSessionNotificationDTO notification);
}

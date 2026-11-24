package cn.qingliao.userservice.service;

import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.common.model.dto.PageRequest;
import cn.qingliao.userservice.constant.*;
import cn.qingliao.userservice.mapper.*;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.FriendDTO;
import cn.qingliao.userservice.model.dto.ModifyFriendApplicationResponse;
import cn.qingliao.userservice.model.dto.request.*;
import cn.qingliao.userservice.model.entity.*;
import cn.qingliao.userservice.model.entity.Friend;
import cn.qingliao.userservice.model.entity.User;
import cn.qingliao.userservice.model.vo.*;
import cn.qingliao.userservice.model.vo.FriendDetailVO;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;

import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

public interface FriendService extends IService<Friend> {

    FriendDetailVO searchUserByKeyword(String userId, String keyword);

    FriendDetailVO getFriendDetails(String userId, String friendId);

    IPage<FriendDTO> getFriends(String userId, PageRequest pageRequest, String key);

    boolean deleteFriend(Long userId, Long friendId);

    boolean blockFriend(Long userId, Long friendId);

    boolean unblockFriend(Long userId, Long friendId);

    ModifyFriendApplicationResponse addFriend(User recipient, Long friendId);
}

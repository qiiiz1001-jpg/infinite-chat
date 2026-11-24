package cn.qingliao.redpacketservice.service;

import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.redpacketservice.constant.*;
import cn.qingliao.redpacketservice.mapper.*;
import cn.qingliao.redpacketservice.model.dto.*;
import cn.qingliao.redpacketservice.model.dto.RedPacketSendRequest;
import cn.qingliao.redpacketservice.model.entity.*;
import cn.qingliao.redpacketservice.model.vo.*;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import org.springframework.web.bind.annotation.*;

import java.math.*;
import java.util.*;

public interface RedPacketValidationService {

    List<Long> validateSendPermission(RedPacketSendRequest request);
}

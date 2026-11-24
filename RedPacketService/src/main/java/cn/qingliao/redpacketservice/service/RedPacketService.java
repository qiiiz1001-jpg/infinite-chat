package cn.qingliao.redpacketservice.service;

import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.redpacketservice.constant.*;
import cn.qingliao.redpacketservice.mapper.*;
import cn.qingliao.redpacketservice.model.dto.*;
import cn.qingliao.redpacketservice.model.dto.RedPacketReceiveRequest;
import cn.qingliao.redpacketservice.model.dto.RedPacketSendRequest;
import cn.qingliao.redpacketservice.model.entity.*;
import cn.qingliao.redpacketservice.model.vo.*;
import cn.qingliao.redpacketservice.model.vo.ReceiveResultVO;
import cn.qingliao.redpacketservice.model.vo.RedPacketBasicVO;
import cn.qingliao.redpacketservice.model.vo.RedPacketDetailVO;
import cn.qingliao.redpacketservice.model.vo.RedPacketSendVO;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import org.springframework.web.bind.annotation.*;

import java.math.*;
import java.util.*;

public interface RedPacketService {

    RedPacketSendVO sendRedPacket(RedPacketSendRequest request);

    ReceiveResultVO receiveRedPacket(RedPacketReceiveRequest request);

    void handleRedPacketReceive(
            Long userId, Long redPacketId, Long receivedAmount, Long receiveTime);

    void handleRedPacketCompleted(Long redPacketId);

    RedPacketDetailVO getRedPacketDetail(Long redPacketId, int pageNum, int pageSize);

    RedPacketBasicVO getRedPacketBasicInfo(Long redPacketId);

    void handleRedPacketExpiration(Long redPacketId);
}

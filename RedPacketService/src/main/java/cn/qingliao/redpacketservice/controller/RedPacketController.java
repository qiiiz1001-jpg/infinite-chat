package cn.qingliao.redpacketservice.controller;

import cn.qingliao.common.common.*;
import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ResultUtils;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.redpacketservice.annotation.PreventDuplicateSubmit;
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
import cn.qingliao.redpacketservice.service.RedPacketService;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.*;
import java.util.*;

@cn.qingliao.common.security.RequireActor
@RestController
@RequestMapping("/api/chat/redPacket")
@Slf4j
public class RedPacketController {

    @Autowired private RedPacketService redPacketService;

    @PostMapping("/send")
    @PreventDuplicateSubmit(expireSeconds = 3)
    public BaseResponse<RedPacketSendVO> sendRedPacket(@RequestBody RedPacketSendRequest request) {
        RedPacketSendVO result = redPacketService.sendRedPacket(request);
        log.info("红包发送成功，红包ID: {}, 消息ID: {}", result.getRedPacketId(), result.getMessageId());
        return ResultUtils.success(result);
    }

    @PostMapping("/receive")
    public BaseResponse<ReceiveResultVO> receiveRedPacket(
            @Valid @RequestBody RedPacketReceiveRequest request) {
        log.info("领取红包请求，用户ID: {}, 红包ID: {}", request.getUserId(), request.getRedPacketId());
        ReceiveResultVO result = redPacketService.receiveRedPacket(request);
        log.info(
                "红包领取处理完成，用户ID: {}, 状态: {}, 金额: {}",
                request.getUserId(),
                result.getStatus(),
                result.getAmount());
        return ResultUtils.success(result);
    }

    @GetMapping("/")
    public BaseResponse<RedPacketDetailVO> getRedPacketDetail(
            @RequestParam("redPacketId") Long redPacketId,
            @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        log.info("查询红包详情，红包ID: {}, pageNum: {}, pageSize: {}", redPacketId, pageNum, pageSize);
        RedPacketDetailVO detail =
                redPacketService.getRedPacketDetail(redPacketId, pageNum, pageSize);
        log.info(
                "红包详情查询完成，红包ID: {}, 状态: {}, 已领取数量: {}",
                redPacketId,
                detail.getStatus(),
                detail.getReceivedCount());
        return ResultUtils.success(detail);
    }

    @GetMapping("/basic")
    public BaseResponse<RedPacketBasicVO> getRedPacketBasicInfo(
            @RequestParam("redPacketId") Long redPacketId) {
        log.info("查询红包基本信息，红包ID: {}", redPacketId);
        RedPacketBasicVO basicInfo = redPacketService.getRedPacketBasicInfo(redPacketId);
        log.info(
                "红包基本信息查询完成，红包ID: {}, 状态: {}, 已领取数量: {}",
                redPacketId,
                basicInfo.getStatus(),
                basicInfo.getReceivedCount());
        return ResultUtils.success(basicInfo);
    }
}

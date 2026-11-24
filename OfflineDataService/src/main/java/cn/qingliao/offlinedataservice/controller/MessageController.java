package cn.qingliao.offlinedataservice.controller;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.common.ResultUtils;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.model.dto.AssistantHistory;
import cn.qingliao.common.model.vo.MessageResponse;
import cn.qingliao.common.security.CurrentUser;
import cn.qingliao.offlinedataservice.model.dto.HistoryMessageRequest;
import cn.qingliao.offlinedataservice.model.dto.OfflineMessageRequest;
import cn.qingliao.offlinedataservice.model.dto.SessionSummaryRequest;
import cn.qingliao.offlinedataservice.service.MessageService;

import jakarta.annotation.Resource;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@cn.qingliao.common.security.RequireActor
@RestController
@RequestMapping("/api/message")
public class MessageController {

    @Resource private MessageService messageService;

    @PostMapping("/offline")
    public BaseResponse<Map<Long, List<MessageResponse>>> getOfflineMessages(
            @RequestBody OfflineMessageRequest request) {
        return ResultUtils.success(messageService.getOfflineMessages(request));
    }

    @PostMapping("/history")
    public BaseResponse<List<MessageResponse>> getHistoryMessages(
            @RequestBody HistoryMessageRequest request) {
        return ResultUtils.success(messageService.getHistoryMessages(request));
    }

    @PostMapping("/internal/assistant-history")
    public BaseResponse<AssistantHistory.Page> assistantHistory(
            @RequestBody AssistantHistory.Query query) {
        if (!CurrentUser.internal()) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return ResultUtils.success(messageService.getAssistantHistory(query));
    }

    @PostMapping("/summary")
    public BaseResponse<String> chatSummary(
            @RequestBody SessionSummaryRequest sessionSummaryRequest) {
        return ResultUtils.success(messageService.getSummary(sessionSummaryRequest));
    }
}

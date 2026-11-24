package cn.qingliao.offlinedataservice.service;

import cn.qingliao.common.model.dto.AssistantHistory;
import cn.qingliao.common.model.dto.MessageRequest;
import cn.qingliao.common.model.vo.MessageResponse;
import cn.qingliao.offlinedataservice.model.dto.HistoryMessageRequest;
import cn.qingliao.offlinedataservice.model.dto.OfflineMessageRequest;
import cn.qingliao.offlinedataservice.model.dto.SessionSummaryRequest;
import cn.qingliao.offlinedataservice.model.entity.Message;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

public interface MessageService extends IService<Message> {

    void saveMessageToMySQL(MessageRequest messageRequest);

    Map<Long, List<MessageResponse>> getOfflineMessages(OfflineMessageRequest request);

    List<MessageResponse> getHistoryMessages(HistoryMessageRequest request);

    AssistantHistory.Page getAssistantHistory(AssistantHistory.Query query);

    String getSummary(SessionSummaryRequest sessionSummaryRequest);
}

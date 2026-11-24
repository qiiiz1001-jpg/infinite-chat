package cn.qingliao.offlinedataservice.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import cn.qingliao.common.model.dto.MessageBody;
import cn.qingliao.common.model.vo.MessageResponse;
import cn.qingliao.offlinedataservice.model.entity.Message;

/** 查询、提交后缓存和 Canal 共用消息结构，避免同一消息生成不同 ZSET member。 */
public final class MessageResponses {
    private MessageResponses() {}

    public static MessageResponse fromEntity(Message message) {
        MessageResponse response = new MessageResponse();
        BeanUtil.copyProperties(message, response);
        response.setCreatedTime(Long.toString(message.getCreatedTime().getTime()));
        MessageBody body =
                Integer.valueOf(3).equals(message.getType())
                        ? JSONUtil.toBean(message.getContent(), MessageBody.class)
                        : new MessageBody();
        if (!Integer.valueOf(3).equals(message.getType())) body.setContent(message.getContent());
        body.setReplyId(message.getReplyId());
        response.setBody(body);
        return response;
    }
}

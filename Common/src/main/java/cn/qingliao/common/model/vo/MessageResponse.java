package cn.qingliao.common.model.vo;

import cn.qingliao.common.model.dto.MessageBody;

import lombok.Data;

@Data
public class MessageResponse {

    private Long sessionId;

    private Long senderId;

    private Long receiverId;

    private Integer type;

    private Integer sessionType;

    private String content;

    private Long replyId;

    private String createdTime;

    private Long messageId;

    private String clientMessageId;

    private String nickname;

    private String avatar;

    private Integer role;

    private MessageBody body;
}

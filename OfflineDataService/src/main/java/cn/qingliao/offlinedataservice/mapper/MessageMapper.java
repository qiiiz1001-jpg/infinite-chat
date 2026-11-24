package cn.qingliao.offlinedataservice.mapper;

import cn.qingliao.offlinedataservice.model.entity.Message;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
    @org.apache.ibatis.annotations.Insert(
            "INSERT INTO"
                + " message(message_id,sender_id,session_id,type,content,reply_id,session_type,created_time)"
                + " VALUES(#{messageId},#{senderId},#{sessionId},#{type},#{content},#{replyId},#{sessionType},#{createdTime})"
                + " ON DUPLICATE KEY UPDATE message_id=message_id")
    int insertIdempotently(Message message);
}

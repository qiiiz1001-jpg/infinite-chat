package cn.qingliao.redpacketservice.mapper;

import cn.qingliao.redpacketservice.model.entity.RedPacketReceive;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RedPacketReceiveMapper extends BaseMapper<RedPacketReceive> {
    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO"
                + " red_packet_receive(red_packet_receive_id,red_packet_id,receiver_id,amount,received_at,created_time,updated_time)"
                + " VALUES(#{redPacketReceiveId},#{redPacketId},#{receiverId},#{amount},#{receivedAt},#{createdTime},#{updatedTime})")
    int insertOnce(cn.qingliao.redpacketservice.model.entity.RedPacketReceive receipt);
}

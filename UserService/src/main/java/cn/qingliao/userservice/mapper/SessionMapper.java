package cn.qingliao.userservice.mapper;

import cn.qingliao.userservice.model.entity.Session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMapper extends BaseMapper<Session> {
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM `session` WHERE session_id=#{sessionId} FOR UPDATE")
    Session selectForUpdate(@org.apache.ibatis.annotations.Param("sessionId") Long sessionId);
}

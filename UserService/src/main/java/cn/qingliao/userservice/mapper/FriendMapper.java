package cn.qingliao.userservice.mapper;

import cn.qingliao.userservice.model.entity.Friend;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FriendMapper extends BaseMapper<Friend> {
    // MyBatis-Plus已提供所有基础CRUD方法
    // 复杂查询使用Lambda Wrapper在Service层实现
}

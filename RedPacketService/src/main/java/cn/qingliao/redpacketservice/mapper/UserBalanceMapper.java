package cn.qingliao.redpacketservice.mapper;

import cn.qingliao.redpacketservice.model.entity.UserBalance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserBalanceMapper extends BaseMapper<UserBalance> {
    @org.apache.ibatis.annotations.Insert(
            "INSERT INTO user_balance(user_id,balance) VALUES(#{userId},#{amount}) ON DUPLICATE KEY"
                    + " UPDATE balance=balance+#{amount}")
    int credit(
            @org.apache.ibatis.annotations.Param("userId") Long userId,
            @org.apache.ibatis.annotations.Param("amount") Long amount);
}

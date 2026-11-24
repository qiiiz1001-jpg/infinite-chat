package cn.qingliao.userservice.service.impl;

import cn.qingliao.userservice.mapper.SessionMapper;
import cn.qingliao.userservice.model.entity.Session;
import cn.qingliao.userservice.service.SessionService;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import org.springframework.stereotype.Service;

@Service
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session>
        implements SessionService {}

package cn.qingliao.userservice.service;

import cn.qingliao.userservice.model.entity.UserSession;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface UserSessionService extends IService<UserSession> {

    List<Long> getUserIdBySessionId(Long sessionId);

    List<Long> getSessionIdsByUserId(Long userId);
}

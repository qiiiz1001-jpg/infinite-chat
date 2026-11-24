package cn.qingliao.userservice.service;

import cn.qingliao.userservice.model.dto.UpdateAvatarRequest;
import cn.qingliao.userservice.model.dto.UserLoginCodeRequest;
import cn.qingliao.userservice.model.dto.UserLoginPasswordRequest;
import cn.qingliao.userservice.model.dto.UserRegisterRequest;
import cn.qingliao.userservice.model.entity.User;
import cn.qingliao.userservice.model.vo.LoginAndRegisterResponse;
import cn.qingliao.userservice.model.vo.TokenResponse;
import cn.qingliao.userservice.model.vo.UploadUrlResponse;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

public interface UserService extends IService<User> {

    void sendCaptcha(String targetEmail);

    LoginAndRegisterResponse register(UserRegisterRequest userRegisterRequest);

    LoginAndRegisterResponse loginPassword(UserLoginPasswordRequest userLoginPasswordRequest);

    LoginAndRegisterResponse loginCode(UserLoginCodeRequest userLoginCodeRequest);

    boolean logout(String userId);

    TokenResponse refreshToken(String refreshToken);

    UploadUrlResponse uploadUrl(String fileName);

    Boolean updateAvatar(UpdateAvatarRequest updateAvatarRequest);

    Map<Long, String> getUserNickName(Long sessionId);

    String refreshUri(Long userId);

    java.util.Map<Long, cn.qingliao.common.model.vo.UserInfosResponse> getUserInfos(
            java.util.List<Long> userIds);
}

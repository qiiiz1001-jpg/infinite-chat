package cn.qingliao.userservice.controller;

import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.common.ErrorCode;
import cn.qingliao.common.common.ResultUtils;
import cn.qingliao.common.exception.ThrowUtils;
import cn.qingliao.common.utils.JwtUtil;
import cn.qingliao.userservice.constant.UserConstant;
import cn.qingliao.userservice.model.dto.UpdateAvatarRequest;
import cn.qingliao.userservice.model.dto.UserLoginCodeRequest;
import cn.qingliao.userservice.model.dto.UserLoginPasswordRequest;
import cn.qingliao.userservice.model.dto.UserRegisterRequest;
import cn.qingliao.userservice.model.vo.LoginAndRegisterResponse;
import cn.qingliao.userservice.model.vo.TokenResponse;
import cn.qingliao.userservice.model.vo.UploadUrlResponse;
import cn.qingliao.userservice.service.UserService;

import io.jsonwebtoken.Claims;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@Validated
public class UserController {

    @Resource private UserService userService;

    @GetMapping("/sendCaptcha")
    public BaseResponse<String> sendCaptcha(
            @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") @RequestParam
                    String targetEmail) {
        userService.sendCaptcha(targetEmail);
        return ResultUtils.success(UserConstant.SEND_EMAIL_SUCCESS);
    }

    @PostMapping("/register")
    public BaseResponse<LoginAndRegisterResponse> register(
            @Valid @RequestBody UserRegisterRequest userRegisterRequest) {
        return ResultUtils.success(userService.register(userRegisterRequest));
    }

    @PostMapping("/login/password")
    public BaseResponse<LoginAndRegisterResponse> loginPassword(
            @Valid @RequestBody UserLoginPasswordRequest userLoginPasswordRequest) {
        return ResultUtils.success(userService.loginPassword(userLoginPasswordRequest));
    }

    @PostMapping("/login/code")
    public BaseResponse<LoginAndRegisterResponse> loginCode(
            @Valid @RequestBody UserLoginCodeRequest userLoginCodeRequest) {
        return ResultUtils.success(userService.loginCode(userLoginCodeRequest));
    }

    @GetMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        String accessToken = request.getHeader("Access-Token");
        Claims claims = JwtUtil.parse(accessToken);
        ThrowUtils.throwIf(claims == null, ErrorCode.NOT_LOGIN_ERROR);
        return ResultUtils.success(userService.logout(claims.getSubject()));
    }

    @GetMapping("/refresh")
    public BaseResponse<TokenResponse> refreshToken(HttpServletRequest request) {
        String refreshToken = request.getHeader("Refresh-Token");
        ThrowUtils.throwIf(StringUtils.isBlank(refreshToken), ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.refreshToken(refreshToken));
    }

    @GetMapping("/refresh/uri")
    public BaseResponse<String> refreshUri(HttpServletRequest request) {
        Claims claims = JwtUtil.parse(request.getHeader("Access-Token"));
        ThrowUtils.throwIf(claims == null, ErrorCode.NOT_LOGIN_ERROR);
        return ResultUtils.success(locator.getServiceInstance(claims.getSubject()));
    }

    @Resource private cn.qingliao.userservice.loadbalancer.NettyServiceLocator locator;

    @GetMapping("/uploadUrl")
    public BaseResponse<UploadUrlResponse> getUploadUrl(@RequestParam String fileName) {
        return ResultUtils.success(userService.uploadUrl(fileName));
    }

    @PostMapping("/update/avatar")
    public BaseResponse<Boolean> updateAvatar(
            @RequestBody UpdateAvatarRequest updateAvatarRequest) {
        return ResultUtils.success(userService.updateAvatar(updateAvatarRequest));
    }

    @GetMapping("/internal/nicknames")
    public Map<Long, String> getUserNickName(@RequestParam("sessionId") Long sessionId) {
        return userService.getUserNickName(sessionId);
    }
}

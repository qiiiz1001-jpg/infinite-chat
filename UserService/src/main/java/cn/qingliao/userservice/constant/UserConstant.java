package cn.qingliao.userservice.constant;

import cn.qingliao.common.common.*;
import cn.qingliao.common.constant.*;
import cn.qingliao.common.exception.*;
import cn.qingliao.common.model.dto.*;
import cn.qingliao.userservice.mapper.*;
import cn.qingliao.userservice.model.dto.*;
import cn.qingliao.userservice.model.dto.request.*;
import cn.qingliao.userservice.model.entity.*;
import cn.qingliao.userservice.model.vo.*;
import cn.qingliao.userservice.service.*;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.baomidou.mybatisplus.core.conditions.update.*;

import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

public class UserConstant {

    public static final String EMAIL_SUBJECT = "【验证码】";

    public static final String SEND_EMAIL_SUCCESS = "发送邮件成功";

    public static final Integer CAPTCHA_EXPIRE_TIME = 5;

    public static final String PASSWORD_SALT = "goat";

    public static final Integer WORKER_ID = 1;

    public static final Integer DATA_CENTER_ID = 1;

    public static final String EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    public static final Integer USER_TIME = 3;
}

package cn.qingliao.common.security;

import java.lang.annotation.*;

/** 核对 Controller 参数里的操作人字段；资源归属、群角色等权限仍由业务层检查。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireActor {}

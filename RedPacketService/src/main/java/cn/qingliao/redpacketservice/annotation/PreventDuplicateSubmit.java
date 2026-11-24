package cn.qingliao.redpacketservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreventDuplicateSubmit {
    String prefix() default "prevent:duplicate:";

    int expireSeconds() default 3;
}

package com.cagritasoz.user_service.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class ExecutionTimeAspect {

    @Pointcut("execution(* com.cagritasoz.user_service.controller.*.*(..))")
    public void controllerMethods() {}


}

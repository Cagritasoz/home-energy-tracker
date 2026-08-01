package com.cagritasoz.user_service.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

import java.util.Arrays;


@Aspect
@Component // Very important to declare it as a bean.
@Slf4j
public class LoggingAspect {

    // All methods under service package.
    @Pointcut("execution(* com.cagritasoz.user_service.service..*.*(..))")
    public void serviceMethods() {}

    @Before("serviceMethods()")
    public void logBefore(JoinPoint joinPoint) {
        log.info("Thread: [{}] called service method: [{}] with arguments: [{}]",
                Thread.currentThread().getName(),
                joinPoint.getSignature().getName(),
                Arrays.toString(joinPoint.getArgs()));

    }

    // Unlike @After which acts like a finally block @AfterReturning runs only after successful method execution
    @AfterReturning(pointcut = "serviceMethods()", returning = "result") //
    public void logAfter(JoinPoint joinPoint, Object result) { // Object "result" should match returning = "result"
        log.info("Thread: [{}] called service method: [{}] returned: [{}]",
                Thread.currentThread().getName(),
                joinPoint.getSignature().getName(),
                result);
    }




}

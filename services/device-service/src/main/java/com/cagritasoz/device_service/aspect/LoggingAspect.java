package com.cagritasoz.device_service.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
@Slf4j
public class LoggingAspect {

    @Pointcut("execution(* com.cagritasoz.device_service.service..*.*(..))")
    public void serviceMethods() {

    }

    @Before("serviceMethods()")
    public void logBefore(JoinPoint joinPoint) {
        log.info("Thread: [{}] called service method: [{}] with arguments: [{}]",
                Thread.currentThread().getName(),
                joinPoint.getSignature().toShortString(),
                Arrays.toString(joinPoint.getArgs()));
    }

    @AfterReturning(pointcut = "serviceMethods()", returning = "result")
    public void logAfterReturning(JoinPoint joinPoint, Object result) {
        log.info("Thread: [{}] called service method: [{}] returned: [{}]",
                Thread.currentThread().getName(),
                joinPoint.getSignature().toShortString(),
                result);
    }

    @AfterThrowing(pointcut = "serviceMethods()", throwing = "ex")
    public void logAfterThrowing(JoinPoint joinPoint, Throwable ex) {
        log.error("Thread: [{}] called service method: [{}] threw exception: [{}]",
                Thread.currentThread().getName(),
                joinPoint.getSignature().toShortString(),
                ex.getMessage());
    }

}

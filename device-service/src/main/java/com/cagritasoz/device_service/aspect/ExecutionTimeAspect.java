package com.cagritasoz.device_service.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
public class ExecutionTimeAspect {

    @Pointcut("execution(* com.cagritasoz.device_service.controller..*.*(..))")
    public void controllerMethods() {
    }

    @Around("controllerMethods()")
    public Object measureExecutionTime(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            return pjp.proceed();
        }
        finally {
            long end = System.nanoTime();
            long elapsedNanoSeconds = end - start;
            long elapsedMilliSeconds = TimeUnit.NANOSECONDS.toMillis(elapsedNanoSeconds);
            log.info("Thread: [{}] called controller method: [{}] executed in: [{}]ms",
                    Thread.currentThread().getName(),
                    pjp.getSignature().toShortString(),
                    elapsedMilliSeconds);
        }
    }
}

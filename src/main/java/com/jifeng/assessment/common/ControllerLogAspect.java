package com.jifeng.assessment.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ControllerLogAspect {

    private final ObjectMapper objectMapper;

    @Pointcut("execution(* com.jifeng.assessment..*Controller.*(..))")
    public void controllerMethods() {}

    @Around("controllerMethods()")
    public Object logRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String method = joinPoint.getSignature().toShortString();
        String user = getCurrentUser();
        String url = getRequestUrl();
        String httpMethod = getHttpMethod();

        Object result;
        int statusCode = 200;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Exception e) {
            statusCode = (e instanceof BusinessException be) ? be.getCode() : 500;
            throw e;
        } finally {
            long elapsed = System.currentTimeMillis() - start;

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("timestamp", Instant.now().toString());
            logEntry.put("type", "API_REQUEST");
            logEntry.put("method", httpMethod);
            logEntry.put("url", url);
            logEntry.put("controller", method);
            logEntry.put("user", user);
            logEntry.put("status", statusCode);
            logEntry.put("elapsed_ms", elapsed);

            try {
                String json = objectMapper.writeValueAsString(logEntry);
                if (statusCode >= 500) {
                    log.error(json);
                } else if (statusCode >= 400) {
                    log.warn(json);
                } else {
                    log.info(json);
                }
            } catch (JsonProcessingException e) {
                log.info("API {} {} {} {}ms status={}", httpMethod, url, user, elapsed, statusCode);
            }
        }
    }

    private String getCurrentUser() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(ServletRequestAttributes::getRequest)
                .map(HttpServletRequest::getUserPrincipal)
                .map(Principal::getName)
                .orElse("anonymous");
    }

    private String getRequestUrl() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(ServletRequestAttributes::getRequest)
                .map(HttpServletRequest::getRequestURI)
                .orElse("unknown");
    }

    private String getHttpMethod() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(ServletRequestAttributes::getRequest)
                .map(HttpServletRequest::getMethod)
                .orElse("unknown");
    }
}

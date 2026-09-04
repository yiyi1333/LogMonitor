package com.logmonitor.security;

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PermissionAspect {
    @Around("@within(com.logmonitor.security.RequirePermission) || @annotation(com.logmonitor.security.RequirePermission)")
    public Object authorize(ProceedingJoinPoint joinPoint) throws Throwable {
        RequirePermission requirement = findRequirement(joinPoint);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new InsufficientAuthenticationException("登录已失效");
        }
        if (user.mustChangePassword() && requirement.value() != AppPermission.CHANGE_OWN_PASSWORD) {
            throw new PasswordChangeRequiredException();
        }
        if (!requirement.value().allows(user.role())) {
            throw new AccessDeniedException("无权执行此操作");
        }
        return joinPoint.proceed();
    }

    private RequirePermission findRequirement(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequirePermission annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequirePermission.class);
        if (annotation != null) return annotation;
        return AnnotatedElementUtils.findMergedAnnotation(joinPoint.getTarget().getClass(), RequirePermission.class);
    }
}

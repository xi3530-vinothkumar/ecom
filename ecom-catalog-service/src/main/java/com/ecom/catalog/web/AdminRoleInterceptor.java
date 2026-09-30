package com.ecom.catalog.web;

import com.ecom.platform.core.CallContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Rejects non-admins on /admin/** before body binding/validation, so a 403 never leaks validation
 * details. Application services re-check the role (defense in depth).
 */
@Configuration(proxyBeanMethods = false)
public class AdminRoleInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/admin/**");
    }
}

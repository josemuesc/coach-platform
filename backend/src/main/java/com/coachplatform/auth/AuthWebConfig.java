package com.coachplatform.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class AuthWebConfig implements WebMvcConfigurer {

    private final SessionFreshnessInterceptor freshness;

    AuthWebConfig(SessionFreshnessInterceptor freshness) {
        this.freshness = freshness;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(freshness).addPathPatterns("/api/**");
    }
}

package com.vllm.gateway.config;

import com.vllm.gateway.filter.AdminJwtFilter;
import com.vllm.gateway.filter.ApiKeyAuthFilter;
import com.vllm.gateway.filter.StreamNoBufferFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<AdminJwtFilter> adminJwtFilterRegistration(AdminJwtFilter filter) {
        FilterRegistrationBean<AdminJwtFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/admin/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<StreamNoBufferFilter> streamNoBufferFilter() {
        FilterRegistrationBean<StreamNoBufferFilter> registration = new FilterRegistrationBean<>(new StreamNoBufferFilter());
        registration.addUrlPatterns("/api/admin/chat/send", "/v1/chat/completions");
        registration.setOrder(0);
        return registration;
    }
}

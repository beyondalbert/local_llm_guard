package com.vllm.gateway.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 为 SSE 流式接口设置禁止反向代理缓冲/缓存的响应头。
 * nginx 只有在识别到 text/event-stream 时才默认不做缓冲，为避免
 * 一次性输出整个流，这里显式下发 X-Accel-Buffering: no 等头。
 * 注意：不标注 @Component，由 FilterConfig 以 @Bean 方式注册，
 * 避免重复注册造成 BeanDefinitionOverrideException。
 */
public class StreamNoBufferFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        boolean streamEndpoint = "/api/admin/chat/send".equals(uri)
                || "/v1/chat/completions".equals(uri);

        if (streamEndpoint) {
            httpResponse.setHeader("X-Accel-Buffering", "no");
            httpResponse.setHeader("Cache-Control", "no-cache");
            httpResponse.setHeader("Connection", "keep-alive");
        }
        chain.doFilter(request, response);
    }
}

package com.vllm.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vllm.gateway.util.JwtUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class AdminJwtFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if ("/api/admin/login".equals(httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = httpRequest.getHeader("Authorization");
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7).trim();
        }

        if (token == null || token.isEmpty()) {
            writeError(httpResponse, "missing_token", "Missing admin token.");
            return;
        }

        String username = jwtUtil.parseUsername(token);
        if (username == null) {
            writeError(httpResponse, "invalid_token", "Invalid or expired admin token.");
            return;
        }

        httpRequest.setAttribute("adminUsername", username);
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of("code", 401, "message", message, "data", code);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

package com.openimage.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        boolean invalidToken = "TOKEN_INVALID".equals(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(
            invalidToken ? "TOKEN_INVALID" : "UNAUTHORIZED",
            invalidToken ? "登录已过期，请重新登录" : "请先登录"
        ));
    }
}

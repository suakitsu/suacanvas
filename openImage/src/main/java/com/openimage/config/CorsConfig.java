package com.openimage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {
    @Value("${openimage.security.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(originPatterns());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    private List<String> originPatterns() {
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
        }
        return List.of("http://localhost:*", "http://127.0.0.1:*");
    }
}

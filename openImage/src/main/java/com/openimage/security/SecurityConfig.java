package com.openimage.security;

import com.openimage.ai.worker.AiWorkerAuthenticationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {
    @Bean
    public SecurityFilterChain chain(
        HttpSecurity http,
        AiWorkerAuthenticationFilter workerFilter,
        JwtAuthenticationFilter jwtFilter,
        UserRequestIpTrackingFilter ipTrackingFilter,
        JsonAuthenticationEntryPoint authenticationEntryPoint,
        JsonAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        return http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                .requestMatchers("/api/internal/picpilot-worker/v1/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/billing/afdian/webhook").permitAll()
                .requestMatchers("/api/billing/ykmcn/notify").permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/public/video-webhooks/apimart",
                    "/api/public/video-webhooks/apimart/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/assistant/status").permitAll()
                .requestMatchers("/api/admin/**").hasAnyRole("admin", "super_admin")
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(workerFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(ipTrackingFilter, JwtAuthenticationFilter.class)
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}

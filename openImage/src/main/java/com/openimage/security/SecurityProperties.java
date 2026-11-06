package com.openimage.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openimage.security")
public record SecurityProperties(String jwtSecret, long accessTokenMinutes) {}

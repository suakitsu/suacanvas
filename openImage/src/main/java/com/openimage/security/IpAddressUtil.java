package com.openimage.security;

import jakarta.servlet.http.HttpServletRequest;

public final class IpAddressUtil {
    private IpAddressUtil() {}

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String item : forwarded.split(",")) {
                String ip = sanitize(item);
                if (!ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
            }
        }
        String realIp = sanitize(request.getHeader("X-Real-IP"));
        if (!realIp.isBlank() && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp;
        }
        String remote = sanitize(request.getRemoteAddr());
        return remote.isBlank() ? "unknown" : remote;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int colonPort = trimmed.lastIndexOf(':');
        if (colonPort > 0 && trimmed.indexOf(':') == colonPort && trimmed.indexOf('.') > 0) {
            trimmed = trimmed.substring(0, colonPort);
        }
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }
}

package com.openimage.security;

public record CurrentUser(String id, String email, String role) {
    public boolean isAdmin() {
        return "admin".equals(role) || "super_admin".equals(role);
    }
}

package com.openimage.modules.auth.email;

public enum EmailCodePurpose {
    REGISTER("register"),
    LOGIN("login"),
    RESET_PASSWORD("reset_password");

    private final String value;

    EmailCodePurpose(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static EmailCodePurpose from(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        for (EmailCodePurpose purpose : values()) {
            if (purpose.value.equals(normalized)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("验证码用途不正确");
    }
}

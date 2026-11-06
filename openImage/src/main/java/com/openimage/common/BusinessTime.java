package com.openimage.common;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class BusinessTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessTime() {}

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDate date(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZONE).toLocalDate();
    }

    public static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toOffsetDateTime();
    }

    public static OffsetDateTime endOfDay(LocalDate date) {
        return startOfDay(date.plusDays(1)).minusNanos(1);
    }
}

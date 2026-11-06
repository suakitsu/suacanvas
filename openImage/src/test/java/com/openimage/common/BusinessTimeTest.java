package com.openimage.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeTest {
    @Test
    void convertsUtcTimestampsUsingShanghaiCalendarDay() {
        OffsetDateTime timestamp = OffsetDateTime.of(2026, 7, 26, 23, 43, 59, 0, ZoneOffset.UTC);

        assertThat(BusinessTime.date(timestamp)).isEqualTo(LocalDate.of(2026, 7, 27));
    }

    @Test
    void createsShanghaiDateBoundariesWithUtcOffsets() {
        LocalDate date = LocalDate.of(2026, 7, 27);

        assertThat(BusinessTime.startOfDay(date))
            .isEqualTo(OffsetDateTime.parse("2026-07-27T00:00:00+08:00"));
        assertThat(BusinessTime.endOfDay(date))
            .isEqualTo(OffsetDateTime.parse("2026-07-27T23:59:59.999999999+08:00"));
    }
}

package com.example.order.coupon;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 시각은 문자열로 받아 오프셋 포함 ISO-8601 만 허용한다(숫자 타임스탬프·오프셋 없는 값은 400).
 * 금액(value, minOrderAmount, maxDiscountAmount)은 Long — int 초과 허용.
 */
public record CouponCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
        @NotNull CouponType type,
        @NotNull Long value,
        @Min(0) Long minOrderAmount,
        @Min(1) Long maxDiscountAmount,
        @NotNull @Min(1) Integer totalQuantity,
        @NotBlank String validFrom,
        @NotBlank String validUntil) {

    @JsonIgnore
    @AssertTrue(message = "value must be >= 1 for FIXED and 1..100 for RATE")
    public boolean isValueValidForType() {
        if (type == null || value == null) {
            return true;
        }
        return switch (type) {
            case FIXED -> value >= 1;
            case RATE -> value >= 1 && value <= 100;
        };
    }

    @JsonIgnore
    @AssertTrue(message = "validFrom/validUntil must be ISO-8601 with offset and validFrom < validUntil")
    public boolean isPeriodValid() {
        if (validFrom == null || validUntil == null || validFrom.isBlank() || validUntil.isBlank()) {
            return true; // @NotBlank 가 보고한다
        }
        Instant from = parse(validFrom);
        Instant until = parse(validUntil);
        return from != null && until != null && from.isBefore(until);
    }

    public Instant validFromInstant() {
        return parse(validFrom);
    }

    public Instant validUntilInstant() {
        return parse(validUntil);
    }

    static Instant parse(String s) {
        try {
            OffsetDateTime odt = OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            int year = odt.toInstant().atOffset(java.time.ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999) {
                return null;
            }
            return odt.toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeException e) {
            return null;
        }
    }
}

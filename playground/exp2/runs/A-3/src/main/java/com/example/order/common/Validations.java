package com.example.order.common;

/** 요청 검증 실패 시 400(VALIDATION_ERROR)을 던지는 작은 도우미. */
public final class Validations {

    private Validations() {
    }

    public static void require(boolean condition, String detail) {
        if (!condition) {
            throw ApiException.validation(detail);
        }
    }

    public static <T> T notNull(T value, String field) {
        require(value != null, field + " is required");
        return value;
    }

    public static void inRange(Long value, long min, long max, String field) {
        notNull(value, field);
        require(value >= min && value <= max, field + " must be between " + min + " and " + max);
    }

    public static void inRange(Integer value, long min, long max, String field) {
        notNull(value, field);
        require(value >= min && value <= max, field + " must be between " + min + " and " + max);
    }

    /** 공백만으로 이뤄지지 않은 1~maxLength 자 문자열. */
    public static void text(String value, int maxLength, String field) {
        notNull(value, field);
        require(!value.isBlank(), field + " must not be blank");
        require(value.codePointCount(0, value.length()) <= maxLength,
                field + " must be at most " + maxLength + " characters");
    }
}

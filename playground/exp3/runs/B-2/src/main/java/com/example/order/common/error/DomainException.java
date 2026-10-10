package com.example.order.common.error;

/** HTTP를 모르는 도메인 예외. 상태코드 매핑은 {@link GlobalExceptionHandler}가 한다. */
public class DomainException extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, CONFLICT, UNPROCESSABLE, UPSTREAM }

    private final Kind kind;
    private final String code;

    private DomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static DomainException invalid(String code, String message) {
        return new DomainException(Kind.INVALID, code, message);
    }

    public static DomainException notFound(String code, String message) {
        return new DomainException(Kind.NOT_FOUND, code, message);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(Kind.CONFLICT, code, message);
    }

    public static DomainException unprocessable(String code, String message) {
        return new DomainException(Kind.UNPROCESSABLE, code, message);
    }

    public static DomainException upstream(String code, String message) {
        return new DomainException(Kind.UPSTREAM, code, message);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}

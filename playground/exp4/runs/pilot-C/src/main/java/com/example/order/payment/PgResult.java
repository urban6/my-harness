package com.example.order.payment;

public record PgResult(Kind kind, String paymentId) {

    public enum Kind { APPROVED, DECLINED, UNAVAILABLE }

    public static PgResult approved(String paymentId) {
        return new PgResult(Kind.APPROVED, paymentId);
    }

    public static PgResult declined() {
        return new PgResult(Kind.DECLINED, null);
    }

    public static PgResult unavailable() {
        return new PgResult(Kind.UNAVAILABLE, null);
    }
}

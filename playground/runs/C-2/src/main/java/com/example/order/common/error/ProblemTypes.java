package com.example.order.common.error;

import java.net.URI;

/** RFC 9457 problem type 식별자. 역참조 가능할 필요는 없는 안정 식별자다. */
public final class ProblemTypes {

    private static final String BASE = "https://example.com/problems/";

    public static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");
    public static final URI MALFORMED_REQUEST = URI.create(BASE + "malformed-request");
    public static final URI PRODUCT_NOT_FOUND = URI.create(BASE + "product-not-found");
    public static final URI ORDER_NOT_FOUND = URI.create(BASE + "order-not-found");
    public static final URI INSUFFICIENT_STOCK = URI.create(BASE + "insufficient-stock");
    public static final URI ORDER_ALREADY_CANCELLED = URI.create(BASE + "order-already-cancelled");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemTypes() {}
}

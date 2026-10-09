package com.example.order.common.error;

import java.net.URI;

public final class ProblemTypes {

    private static final String PREFIX = "https://example.com/problems/";

    public static final URI VALIDATION_ERROR = URI.create(PREFIX + "validation-error");
    public static final URI MALFORMED_REQUEST = URI.create(PREFIX + "malformed-request");
    public static final URI INVALID_PARAMETER = URI.create(PREFIX + "invalid-parameter");
    public static final URI AMOUNT_OVERFLOW = URI.create(PREFIX + "amount-overflow");
    public static final URI PRODUCT_NOT_FOUND = URI.create(PREFIX + "product-not-found");
    public static final URI ORDER_NOT_FOUND = URI.create(PREFIX + "order-not-found");
    public static final URI INSUFFICIENT_STOCK = URI.create(PREFIX + "insufficient-stock");
    public static final URI ORDER_ALREADY_CANCELLED = URI.create(PREFIX + "order-already-cancelled");
    public static final URI INTERNAL_ERROR = URI.create(PREFIX + "internal-error");

    private ProblemTypes() {
    }
}

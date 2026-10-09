package com.example.order.common.error;

import java.net.URI;

public final class ProblemTypes {

    private static final String BASE = "https://example.com/problems/";

    public static final URI VALIDATION_FAILED = URI.create(BASE + "validation-failed");
    public static final URI MALFORMED_REQUEST_BODY = URI.create(BASE + "malformed-request-body");
    public static final URI INVALID_PATH_PARAMETER = URI.create(BASE + "invalid-path-parameter");
    public static final URI PRODUCT_NOT_FOUND = URI.create(BASE + "product-not-found");

    public static final String TITLE_VALIDATION_FAILED = "Validation Failed";
    public static final String TITLE_MALFORMED_REQUEST_BODY = "Malformed Request Body";
    public static final String TITLE_INVALID_PATH_PARAMETER = "Invalid Path Parameter";
    public static final String TITLE_PRODUCT_NOT_FOUND = "Product Not Found";
    public static final String TITLE_INTERNAL_SERVER_ERROR = "Internal Server Error";

    private ProblemTypes() {
    }
}

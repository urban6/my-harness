package com.example.order.common;

import java.util.ArrayList;
import java.util.List;

/** 400(VALIDATION_ERROR) 사유를 모아 한 번에 던진다. */
public final class Violations {

    private final List<String> messages = new ArrayList<>();

    public Violations check(boolean ok, String message) {
        if (!ok) {
            messages.add(message);
        }
        return this;
    }

    public void throwIfAny() {
        if (!messages.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, String.join("; ", messages));
        }
    }

    public static ApiException error(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}

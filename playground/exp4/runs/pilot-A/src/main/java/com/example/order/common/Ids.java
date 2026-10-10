package com.example.order.common;

public final class Ids {

    private Ids() {
    }

    /** 경로의 id를 해석한다. 해석할 수 없으면 그런 리소스가 없는 것으로 보고 해당 404를 던진다. */
    public static long parse(String raw, ErrorCode notFound) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(notFound, "Resource not found: " + raw);
        }
    }
}

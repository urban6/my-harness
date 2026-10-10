package com.example.order.common;

public final class PathIds {

    private PathIds() {
    }

    /** 경로의 숫자 ID를 해석한다. 해석할 수 없으면 해당 리소스는 존재할 수 없으므로 404. */
    public static long parse(String raw, String notFoundCode, String resource) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.notFound(notFoundCode, resource + " " + raw + " not found");
        }
    }
}

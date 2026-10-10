package com.example.order.order;

import com.example.order.common.Problems;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Opaque cursor: Base64URL(no padding) of "v1:" + lastId. */
public final class Cursor {

    private static final Pattern PAYLOAD = Pattern.compile("^v1:[1-9][0-9]{0,18}$");

    private Cursor() {
    }

    public static String encode(long lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1:" + lastId).getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String cursor) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            String payload = new String(raw, StandardCharsets.UTF_8);
            if (!PAYLOAD.matcher(payload).matches()) {
                throw Problems.invalidCursor();
            }
            long id = Long.parseLong(payload.substring(3));
            if (!encode(id).equals(cursor)) { // reject non-canonical encodings
                throw Problems.invalidCursor();
            }
            return id;
        } catch (IllegalArgumentException e) { // bad base64 or Long overflow
            throw Problems.invalidCursor();
        }
    }
}

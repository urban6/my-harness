package com.example.order.idempotency;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 멱등 키로 저장·재생되는 2xx 응답. */
public record StoredResponse(int status, String body, String location) {

    public ResponseEntity<byte[]> toResponseEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (location != null) {
            headers.set(HttpHeaders.LOCATION, location);
        }
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), headers, status);
    }
}

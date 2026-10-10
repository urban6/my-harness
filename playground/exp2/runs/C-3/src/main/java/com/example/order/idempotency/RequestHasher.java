package com.example.order.idempotency;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class RequestHasher {

    private final ObjectMapper canonicalMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    /** canonical = scope \n userId(없으면 "") \n "POST " path \n canonicalJson(dto) -> SHA-256 hex */
    public String hash(IdempotencyScope scope, String userId, String path, Object requestDto) {
        String json;
        try {
            json = canonicalMapper.writeValueAsString(requestDto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("요청 직렬화 실패", e);
        }
        String canonical = scope.name() + "\n"
                + (userId == null ? "" : userId) + "\n"
                + "POST " + path + "\n"
                + json;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

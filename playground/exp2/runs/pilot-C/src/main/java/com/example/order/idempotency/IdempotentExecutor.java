package com.example.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** begin -> 업무 처리(자체 트랜잭션) -> complete/release. 이 클래스와 호출 컨트롤러는 트랜잭션을 열지 않는다. */
@Component
public class IdempotentExecutor {
    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final IdempotencyService service;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(IdempotencyService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** 요청 지문 (설계 01 4.2). xUserId == null 이면 "-". */
    public String fingerprint(IdempotencyScope scope, String xUserId, String path, Object requestDto) {
        try {
            String canonicalBody = CANONICAL_MAPPER.writeValueAsString(requestDto);
            String userPart = xUserId == null ? "-" : "U:" + xUserId;
            String src = scope.name() + "\n" + userPart + "\n" + path + "\n" + canonicalBody;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(src.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ResponseEntity<Object> execute(IdempotencyScope scope, String key, String fingerprint,
                                          Supplier<IdemResult> action) {
        BeginResult begun = service.begin(scope, key, fingerprint);
        if (begun instanceof BeginResult.Replay replay) {
            try {
                return response(replay.status(), objectMapper.readTree(replay.body()), replay.location());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        long id = ((BeginResult.Owner) begun).id();
        IdemResult result;
        try {
            result = action.get();
        } catch (RuntimeException | Error t) {
            service.release(id);
            throw t;
        }
        if (result.status() >= 200 && result.status() < 300) {
            try {
                service.complete(id, result.status(), objectMapper.writeValueAsString(result.body()), result.location());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        } else {
            service.release(id);
        }
        return response(result.status(), result.body(), result.location());
    }

    private static ResponseEntity<Object> response(int status, Object body, String location) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON);
        if (location != null) {
            b.header(HttpHeaders.LOCATION, location);
        }
        return b.body(body);
    }
}

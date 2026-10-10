package com.example.order.idempotency;

import com.example.order.common.ApiException;
import com.example.order.common.ApiResult;
import com.example.order.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 멱등성 파사드 (R4). 트랜잭션에 속하지 않는다 — 커넥션을 중첩 점유하지 않기 위함.
 * claim → (처리: 2xx 면 action 내부 트랜잭션에서 complete) / 오류면 claim 해제.
 */
@Component
public class IdempotencyFacade {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFacade.class);
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final IdempotencyStore store;

    public IdempotencyFacade(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * @param action claim 된 레코드 id 를 받아 처리하고, 자신의 트랜잭션 안에서 {@link IdempotencyStore#complete} 를 호출한 뒤
     *               ApiResult 를 돌려준다. 어떤 예외든 던지면 키가 해제된다.
     */
    public ApiResult execute(IdempotencyScope scope, String key, String userId, String path,
                             String normalizedBody, LongFunction<ApiResult> action) {
        String hash = sha256Hex(normalizedBody);
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Optional<Long> claimed = store.claim(scope, key, userId, path, hash);
            if (claimed.isPresent()) {
                long id = claimed.get();
                try {
                    return action.apply(id);
                } catch (RuntimeException | Error e) {
                    releaseQuietly(id);
                    throw e;
                }
            }
            Optional<IdempotencyRecord> existing = store.find(scope, key);
            if (existing.isEmpty()) {
                continue; // 소유자가 해제함 → 재선점 시도
            }
            IdempotencyRecord r = existing.get();
            if (!Objects.equals(r.userId(), userId) || !r.requestPath().equals(path) || !r.bodyHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "Idempotency-Key was already used with a different request.");
            }
            if (r.isCompleted()) {
                return new ApiResult(r.responseStatus(), r.responseBody(), r.responseLocation());
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with the same Idempotency-Key is still being processed.");
        }
        throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "A request with the same Idempotency-Key is still being processed.");
    }

    private void releaseQuietly(long id) {
        try {
            store.release(id);
        } catch (RuntimeException e) {
            log.error("Failed to release idempotency record {}", id, e);
        }
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

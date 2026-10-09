package com.example.order.idempotency;

import com.example.order.common.error.ApiException;
import com.example.order.common.error.ErrorCode;
import com.example.order.common.time.TimeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 멱등 처리(R4). 클레임·해제는 별도 트랜잭션(즉시 커밋), 완료 기록은 호출자(비즈니스) 트랜잭션에 참여한다.
 */
@Service
public class IdempotencyService {

    private static final int MAX_CLAIM_ATTEMPTS = 3;

    /** 지문 계산 전용(기본 설정). 응답 직렬화용 매퍼와 분리한다. */
    private final ObjectMapper fingerprintMapper = new ObjectMapper();
    private final ObjectMapper responseMapper;
    private final IdempotencyRecordRepository repository;
    private final TimeProvider time;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(ObjectMapper responseMapper, IdempotencyRecordRepository repository,
                              TimeProvider time, PlatformTransactionManager txManager) {
        this.responseMapper = responseMapper;
        this.repository = repository;
        this.time = time;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** SHA-256 hex( userId \n "POST " path \n canonicalBody ). */
    public String fingerprint(String userId, String path, Object requestBody) {
        String canonical;
        try {
            canonical = fingerprintMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("요청 본문 직렬화 실패", e);
        }
        String raw = (userId == null ? "" : userId) + "\n" + "POST " + path + "\n" + canonical;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 클레임 후 action을 실행한다. 재생이면 action을 호출하지 않고 저장된 응답을 반환한다.
     * action 안에서 예외가 나면 레코드를 삭제하고(R4.4) 예외를 그대로 전파한다.
     */
    public StoredResponse execute(IdempotencyScope scope, String key, String fingerprint,
                                  LongFunction<StoredResponse> action) {
        Claim claim = claim(scope, key, fingerprint);
        if (claim instanceof Claim.Replay replay) {
            return replay.response();
        }
        long recordId = ((Claim.Owned) claim).recordId();
        try {
            return action.apply(recordId);
        } catch (RuntimeException | Error e) {
            try {
                release(recordId);
            } catch (RuntimeException releaseFailure) {
                e.addSuppressed(releaseFailure);
            }
            throw e;
        }
    }

    /** 비즈니스 트랜잭션 안에서 호출: 응답을 직렬화해 레코드를 COMPLETED로 갱신하고 그 응답을 돌려준다. */
    @Transactional
    public StoredResponse complete(long recordId, int status, Object body, String location) {
        String json;
        try {
            json = responseMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 직렬화 실패", e);
        }
        repository.markCompleted(recordId, IdempotencyStatus.COMPLETED, status, json, location, time.now());
        return new StoredResponse(status, json, location);
    }

    private Claim claim(IdempotencyScope scope, String key, String fingerprint) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Claim result = requiresNew.execute(status -> tryClaimOnce(scope, key, fingerprint));
            if (result != null) {
                return result;
            }
        }
        throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "같은 멱등 키의 요청이 처리 중입니다.");
    }

    /** null이면 소유자가 방금 실패해 레코드가 사라진 것 → 재시도. */
    private Claim tryClaimOnce(IdempotencyScope scope, String key, String fingerprint) {
        int inserted = repository.tryClaim(scope.name(), key, fingerprint, time.now());
        Optional<IdempotencyRecord> existing = repository.findByScopeAndIdemKey(scope, key);
        if (existing.isEmpty()) {
            return null;
        }
        IdempotencyRecord record = existing.get();
        if (inserted == 1) {
            return new Claim.Owned(record.getId());
        }
        if (!record.getFingerprint().equals(fingerprint)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH, "같은 멱등 키로 다른 요청이 전달되었습니다.");
        }
        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "같은 멱등 키의 요청이 처리 중입니다.");
        }
        return new Claim.Replay(new StoredResponse(
                record.getResponseStatus(), record.getResponseBody(), record.getResponseLocation()));
    }

    private void release(long recordId) {
        requiresNew.executeWithoutResult(status ->
                repository.releaseInProgress(recordId, IdempotencyStatus.IN_PROGRESS));
    }

    private sealed interface Claim {
        record Owned(long recordId) implements Claim {
        }

        record Replay(StoredResponse response) implements Claim {
        }
    }
}

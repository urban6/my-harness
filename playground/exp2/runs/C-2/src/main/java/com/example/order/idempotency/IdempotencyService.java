package com.example.order.idempotency;

import com.example.order.common.error.IdempotencyInProgressException;
import com.example.order.common.error.IdempotencyKeyMismatchException;
import com.example.order.idempotency.IdempotencyRepository.Row;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 멱등 키 처리 (01 설계 6.4). 이 서비스는 트랜잭션을 열지 않는다.
 * begin()/abandon()은 각자 짧은 autocommit 이고, complete()는 호출자의 비즈니스 트랜잭션에 참여한다.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int MAX_ATTEMPTS = 3;

    /** replay가 있으면 처리 없이 그대로 응답, 없으면 recordId 소유권을 얻은 것. */
    public record Begin(Long recordId, IdempotentResponse replay) {
    }

    private final IdempotencyRepository repository;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Begin begin(IdempotencyScope scope, String key, String fingerprint) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<Long> inserted = repository.tryInsert(scope, key, fingerprint, clock.instant().truncatedTo(ChronoUnit.MICROS));
            if (inserted.isPresent()) {
                return new Begin(inserted.get(), null);
            }
            Optional<Row> existing = repository.find(scope, key);
            if (existing.isEmpty()) {
                continue; // 그 사이 삭제됨 -> 다시 시도
            }
            Row row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyMismatchException("같은 Idempotency-Key로 다른 요청이 전송되었습니다.");
            }
            if (row.completed()) {
                return new Begin(null, new IdempotentResponse(row.responseStatus(), row.responseBody(), row.responseLocation()));
            }
            throw new IdempotencyInProgressException("같은 Idempotency-Key의 요청이 처리 중입니다.");
        }
        throw new IdempotencyInProgressException("같은 Idempotency-Key의 요청이 처리 중입니다.");
    }

    /** 비즈니스 트랜잭션 안에서 호출: 반영과 응답 저장이 원자적이다. */
    public void complete(long recordId, IdempotentResponse response) {
        int updated = repository.complete(recordId, response.status(), response.body(), response.locationPath(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (updated != 1) {
            throw new IllegalStateException("idempotency record " + recordId + " is not IN_PROGRESS");
        }
    }

    /** 비2xx로 끝난 요청의 키를 풀어 같은 요청으로 재시도할 수 있게 한다 (R4.4). 트랜잭션 밖에서 호출. */
    public void abandon(long recordId) {
        try {
            repository.deleteInProgress(recordId);
        } catch (RuntimeException e) {
            log.error("Failed to release idempotency record {}", recordId, e);
        }
    }
}

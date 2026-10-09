package com.example.order.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    @Modifying
    @Query(value = "INSERT INTO idempotency_records (scope, idem_key, fingerprint, status, created_at) "
            + "VALUES (:scope, :key, :fp, 'IN_PROGRESS', :now) ON CONFLICT (scope, idem_key) DO NOTHING",
            nativeQuery = true)
    int tryClaim(@Param("scope") String scope, @Param("key") String key,
                 @Param("fp") String fp, @Param("now") Instant now);

    Optional<IdempotencyRecord> findByScopeAndIdemKey(IdempotencyScope scope, String idemKey);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.id = :id and r.status = :status")
    int releaseInProgress(@Param("id") Long id, @Param("status") IdempotencyStatus status);

    @Modifying
    @Query("update IdempotencyRecord r set r.status = :status, r.responseStatus = :rs, r.responseBody = :body, "
            + "r.responseLocation = :loc, r.completedAt = :at where r.id = :id")
    int markCompleted(@Param("id") Long id, @Param("status") IdempotencyStatus status,
                      @Param("rs") Integer responseStatus, @Param("body") String body,
                      @Param("loc") String location, @Param("at") Instant completedAt);
}

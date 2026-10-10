package com.example.order.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    /**
     * Claims a key. Returns 1 when claimed, 0 when it already exists. A concurrent in-flight claim makes this
     * statement wait until the other transaction commits (0 rows) or rolls back (1 row).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO idempotency_keys (operation, scope_key, idem_key, request_hash, created_at) "
            + "VALUES (:operation, :scope, :key, :hash, :now) "
            + "ON CONFLICT (operation, scope_key, idem_key) DO NOTHING", nativeQuery = true)
    int claim(@Param("operation") String operation, @Param("scope") String scope, @Param("key") String key,
            @Param("hash") String hash, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update IdempotencyKey k set k.orderId = :orderId, k.outcome = :outcome "
            + "where k.operation = :operation and k.scopeKey = :scope and k.idemKey = :key")
    int complete(@Param("operation") String operation, @Param("scope") String scope, @Param("key") String key,
            @Param("orderId") long orderId, @Param("outcome") String outcome);

    Optional<IdempotencyKey> findByOperationAndScopeKeyAndIdemKey(String operation, String scopeKey, String idemKey);
}

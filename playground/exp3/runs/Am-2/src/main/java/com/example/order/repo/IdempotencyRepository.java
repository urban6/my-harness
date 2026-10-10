package com.example.order.repo;

import com.example.order.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndIdemKey(String userId, String idemKey);
}

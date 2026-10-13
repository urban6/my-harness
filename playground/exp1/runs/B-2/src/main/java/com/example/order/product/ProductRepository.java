package com.example.order.product;

import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 재고 변경용 비관적 잠금. id 오름차순으로 잠가 트랜잭션 간 교착을 피한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Product> findByIdInOrderByIdAsc(Collection<Long> ids);
}

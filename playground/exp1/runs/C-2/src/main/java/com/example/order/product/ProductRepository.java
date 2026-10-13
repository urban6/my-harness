package com.example.order.product;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 행 락(SELECT ... FOR UPDATE)을 id 오름차순 단일 쿼리로 획득한다.
     * 트랜잭션에서 Product를 읽는 첫 쿼리여야 한다(1차 캐시의 낡은 stock 방지).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id asc")
    List<Product> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);
}

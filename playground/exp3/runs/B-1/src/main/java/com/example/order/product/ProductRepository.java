package com.example.order.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 재고 변경은 모두 조건부 단일 UPDATE 로 수행해 동시 요청에서도 초과 예약이 생기지 않게 한다.
 * 여러 상품을 건드릴 때는 호출 측이 productId 오름차순으로 호출해 데드락을 피한다.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 가용 재고가 충분할 때만 예약. 반환값 0이면 재고 부족. */
    @Modifying
    @Query("update Product p set p.reserved = p.reserved + :qty where p.id = :id and p.stock - p.reserved >= :qty")
    int reserve(@Param("id") long id, @Param("qty") int qty);

    @Modifying
    @Query("update Product p set p.reserved = p.reserved - :qty where p.id = :id")
    int release(@Param("id") long id, @Param("qty") int qty);

    /** 결제 확정: 예약분을 실재고에서 차감. */
    @Modifying
    @Query("update Product p set p.stock = p.stock - :qty, p.reserved = p.reserved - :qty where p.id = :id")
    int commit(@Param("id") long id, @Param("qty") int qty);

    /** 환불: 차감했던 실재고 복원. */
    @Modifying
    @Query("update Product p set p.stock = p.stock + :qty where p.id = :id")
    int restock(@Param("id") long id, @Param("qty") int qty);
}

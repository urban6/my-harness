package com.example.order.product;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByIdIn(Collection<Long> ids);

    /** 가용 재고가 충분할 때만 원자적으로 예약한다. 0이면 재고 부족. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved + :qty where p.id = :id and p.stock - p.reserved >= :qty")
    int reserve(@Param("id") long id, @Param("qty") long qty);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved - :qty where p.id = :id")
    int release(@Param("id") long id, @Param("qty") long qty);

    /** 결제 완료: 예약분을 실재고에서 차감한다. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :qty, p.reserved = p.reserved - :qty where p.id = :id")
    int commit(@Param("id") long id, @Param("qty") long qty);

    /** 환불: 차감했던 실재고를 되돌린다. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :qty where p.id = :id")
    int restock(@Param("id") long id, @Param("qty") long qty);
}

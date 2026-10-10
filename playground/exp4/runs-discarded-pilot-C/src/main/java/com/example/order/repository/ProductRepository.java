package com.example.order.repository;

import com.example.order.domain.Product;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    interface PriceView {
        Long getId();

        Long getPrice();
    }

    /** 엔티티를 영속성 컨텍스트에 올리지 않는 락 없는 존재/가격 조회 (상품은 불변). */
    @Query("select p.id as id, p.price as price from Product p where p.id in :ids")
    List<PriceView> findPrices(@Param("ids") Collection<Long> ids);

    /** 재고 예약 (R10.1). 0행이면 INSUFFICIENT_STOCK. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved + :q where p.id = :id and p.stock - p.reserved >= :q")
    int reserve(@Param("id") long id, @Param("q") long q);

    /** 예약 해제 (취소/만료/거절). */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved - :q where p.id = :id")
    int release(@Param("id") long id, @Param("q") long q);

    /** 결제 승인: stock 과 reserved 를 함께 차감. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :q, p.reserved = p.reserved - :q where p.id = :id")
    int confirmSale(@Param("id") long id, @Param("q") long q);

    /** 환불: stock 만 복원. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :q where p.id = :id")
    int restock(@Param("id") long id, @Param("q") long q);
}

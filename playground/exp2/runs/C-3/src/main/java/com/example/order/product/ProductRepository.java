package com.example.order.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 0이면 재고 부족 -> 409 INSUFFICIENT_STOCK */
    @Modifying
    @Query(value = "UPDATE products SET reserved = reserved + :qty WHERE id = :id AND stock - reserved >= :qty",
            nativeQuery = true)
    int tryReserve(@Param("id") long id, @Param("qty") int qty);

    /** 거절·만료·취소 */
    @Modifying
    @Query(value = "UPDATE products SET reserved = reserved - :qty WHERE id = :id", nativeQuery = true)
    int releaseReservation(@Param("id") long id, @Param("qty") int qty);

    /** 결제 승인 */
    @Modifying
    @Query(value = "UPDATE products SET stock = stock - :qty, reserved = reserved - :qty WHERE id = :id",
            nativeQuery = true)
    int commitReservation(@Param("id") long id, @Param("qty") int qty);

    /** 환불 */
    @Modifying
    @Query(value = "UPDATE products SET stock = stock + :qty WHERE id = :id", nativeQuery = true)
    int restock(@Param("id") long id, @Param("qty") int qty);
}

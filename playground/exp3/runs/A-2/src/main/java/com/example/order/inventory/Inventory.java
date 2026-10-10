package com.example.order.inventory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 재고 수량 변경은 조건부 UPDATE 한 문장으로 원자적으로 처리해 동시 주문에서도 초과 예약이 생기지 않게 한다.
 * 호출하는 트랜잭션에 합류하며, 데드락 방지를 위해 호출자는 productId 오름차순으로 호출해야 한다.
 */
@Component
public class Inventory {

    private final JdbcClient jdbc;

    public Inventory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 가용 재고(stock - reserved)가 충분할 때만 예약한다. */
    public boolean reserve(long productId, int quantity) {
        return jdbc.sql("update products set reserved = reserved + :q where id = :id and stock - reserved >= :q")
                .param("q", quantity).param("id", productId).update() == 1;
    }

    /** 예약 해제 (취소·만료·결제 실패). */
    public void release(long productId, int quantity) {
        expectOne(jdbc.sql("update products set reserved = reserved - :q where id = :id and reserved >= :q")
                .param("q", quantity).param("id", productId).update(), "release", productId);
    }

    /** 결제 확정: 예약분을 실재고에서 차감. */
    public void commit(long productId, int quantity) {
        expectOne(jdbc.sql("update products set stock = stock - :q, reserved = reserved - :q "
                        + "where id = :id and reserved >= :q")
                .param("q", quantity).param("id", productId).update(), "commit", productId);
    }

    /** 환불: 차감했던 실재고 복원. */
    public void restock(long productId, int quantity) {
        expectOne(jdbc.sql("update products set stock = stock + :q where id = :id")
                .param("q", quantity).param("id", productId).update(), "restock", productId);
    }

    private static void expectOne(int updated, String op, long productId) {
        if (updated != 1) {
            throw new IllegalStateException("Inventory " + op + " failed for product " + productId);
        }
    }
}

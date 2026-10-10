package com.example.order.product;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import com.example.order.common.error.DomainException;
import com.example.order.product.dto.CreateProductRequest;
import com.example.order.product.dto.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product saved = productRepository.save(new Product(request.name(), request.price(), request.stock()));
        return ProductResponse.from(saved);
    }

    public ProductResponse get(Long id) {
        return ProductResponse.from(productRepository.findById(id).orElseThrow(() -> notFound(id)));
    }

    // 아래 재고 메서드는 호출자(주문) 트랜잭션 안에서만 쓴다. 데드락 방지를 위해 항상 productId 오름차순으로 처리한다.

    /** 재고를 예약하고 상품별 현재 단가를 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Long> reserve(SortedMap<Long, Integer> quantities) {
        Map<Long, Long> prices = new HashMap<>();
        for (Product p : productRepository.findAllById(quantities.keySet())) {
            prices.put(p.getId(), p.getPrice());
        }
        for (Long id : quantities.keySet()) {
            if (!prices.containsKey(id)) {
                throw notFound(id);
            }
        }
        quantities.forEach((id, qty) -> {
            if (productRepository.reserve(id, qty) == 0) {
                throw DomainException.conflict("INSUFFICIENT_STOCK", "재고가 부족합니다: productId=" + id);
            }
        });
        return prices;
    }

    /** 예약 해제(주문 취소·만료·결제 실패). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(SortedMap<Long, Integer> quantities) {
        quantities.forEach((id, qty) -> requireUpdated(productRepository.release(id, qty), "release", id));
    }

    /** 결제 확정: 예약분을 실재고에서 차감한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(SortedMap<Long, Integer> quantities) {
        quantities.forEach((id, qty) -> requireUpdated(productRepository.confirm(id, qty), "confirm", id));
    }

    /** 환불: 차감했던 실재고를 되돌린다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(SortedMap<Long, Integer> quantities) {
        quantities.forEach((id, qty) -> requireUpdated(productRepository.restock(id, qty), "restock", id));
    }

    // 예약이 있어야 하는 곳에서 0행이면 불변식 위반(버그)이므로 트랜잭션을 롤백시킨다.
    private static void requireUpdated(int rows, String op, Long id) {
        if (rows == 0) {
            throw new IllegalStateException("재고 " + op + " 실패: productId=" + id);
        }
    }

    private static DomainException notFound(Long id) {
        return DomainException.notFound("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: id=" + id);
    }
}

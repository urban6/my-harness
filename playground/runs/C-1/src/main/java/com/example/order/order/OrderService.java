package com.example.order.order;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.order.common.web.PageResponse;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderItemRequest;
import com.example.order.order.dto.OrderResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductNotFoundException;
import com.example.order.product.ProductRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /** 01 §7.1 — 단일 트랜잭션 + 조건부 원자 UPDATE. */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<OrderItemRequest> items = request.items();

        // [2] 존재 확인(404) — 어떤 차감보다 먼저 전부 끝낸다.
        List<Long> ids = items.stream().map(OrderItemRequest::productId).toList();
        Map<Long, Product> products = new HashMap<>();
        for (Product p : productRepository.findAllById(ids)) {
            products.put(p.getId(), p);
        }
        List<Long> missing = ids.stream()
                .filter(id -> !products.containsKey(id))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ProductNotFoundException(missing);
        }

        // 금액 계산(400 overflow) — 404 다음, 재고 차감(409) 전.
        long total = 0;
        try {
            for (OrderItemRequest item : items) {
                long unitPrice = products.get(item.productId()).getPrice();
                total = Math.addExact(total, Math.multiplyExact(unitPrice, (long) item.quantity()));
            }
        } catch (ArithmeticException e) {
            throw new AmountOverflowException();
        }

        // [3] 재고 차감(409) — productId 오름차순으로 데드락 회피. 실패 시 예외로 전체 롤백.
        items.stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .forEach(item -> {
                    int updated = productRepository.decreaseStock(item.productId(), item.quantity());
                    if (updated == 0) {
                        throw new InsufficientStockException(item.productId(), item.quantity());
                    }
                });

        // 주문 저장 — 요청 순서대로 항목을 추가한다.
        Order order = new Order(total);
        for (OrderItemRequest item : items) {
            long unitPrice = products.get(item.productId()).getPrice();
            order.addItem(new OrderItem(order, item.productId(), item.quantity(), unitPrice));
        }
        orderRepository.save(order);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    /** 01 §7.2 — 주문 행 비관적 락 + 원자 증가 UPDATE. */
    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        order.cancel();
        order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .forEach(item -> productRepository.increaseStock(item.getProductId(), item.getQuantity()));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(int page, int size) {
        // offset이 Integer.MAX_VALUE를 넘으면 Spring Data가 예외를 던진다. 설계(§3.4)상 범위를 넘는 페이지는
        // 200 + 빈 content + 실제 totalElements이므로 쿼리를 건너뛴다.
        if ((long) page * size > Integer.MAX_VALUE) {
            return new PageResponse<>(List.of(), page, size, orderRepository.count());
        }
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<OrderResponse> result = orderRepository.findAll(pageable).map(OrderResponse::from);
        return PageResponse.of(result, page, size);
    }
}

package com.example.order.order;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderItemRequest;
import com.example.order.order.dto.OrderPageResponse;
import com.example.order.order.dto.OrderResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductNotFoundException;
import com.example.order.product.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    /**
     * 모든 항목의 상품을 잠근 뒤 존재(404) → 재고(409) 순으로 확인한다.
     * 하나라도 실패하면 예외로 트랜잭션이 롤백되어 재고는 전혀 차감되지 않는다.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Map<Long, Product> products = lockProducts(request.items().stream().map(OrderItemRequest::productId).toList());
        for (OrderItemRequest item : request.items()) {
            if (!products.containsKey(item.productId())) {
                throw new ProductNotFoundException(item.productId());
            }
        }

        // PostgreSQL timestamptz 정밀도(마이크로초)에 맞춰 저장 전후 값이 같도록 한다.
        Order order = new Order(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        for (OrderItemRequest item : request.items()) {
            Product product = products.get(item.productId());
            product.decreaseStock(item.quantity());
            order.addItem(product.getId(), item.quantity(), product.getPrice());
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    public OrderResponse getById(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        order.cancel();

        Map<Long, Product> products = lockProducts(order.getItems().stream().map(OrderItem::getProductId).toList());
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).increaseStock(item.getQuantity());
        }
        return OrderResponse.from(order);
    }

    public OrderPageResponse list(int page, int size) {
        return OrderPageResponse.from(
                orderRepository.findAll(PageRequest.of(page, size, NEWEST_FIRST)).map(OrderResponse::from));
    }

    private Map<Long, Product> lockProducts(List<Long> productIds) {
        return productRepository.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }
}

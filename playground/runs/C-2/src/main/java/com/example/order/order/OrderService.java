package com.example.order.order;

import com.example.order.common.PageResponse;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderItemRequest;
import com.example.order.order.dto.OrderItemResponse;
import com.example.order.order.dto.OrderResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductNotFoundException;
import com.example.order.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<OrderItemRequest> items = request.items();
        List<Long> ids = items.stream().map(OrderItemRequest::productId).sorted().toList();

        // 이 트랜잭션에서 Product를 읽는 첫 쿼리 (락 + 최신 stock)
        Map<Long, Product> products = new HashMap<>();
        for (Product p : productRepository.findAllByIdInForUpdate(ids)) {
            products.put(p.getId(), p);
        }

        List<Long> missing = ids.stream().filter(id -> !products.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ProductNotFoundException(missing);
        }

        List<InsufficientStockException.Shortage> shortages = new ArrayList<>();
        for (OrderItemRequest item : items) {
            Product p = products.get(item.productId());
            if (p.getStock() < item.quantity()) {
                shortages.add(new InsufficientStockException.Shortage(p.getId(), item.quantity(), p.getStock()));
            }
        }
        if (!shortages.isEmpty()) {
            throw new InsufficientStockException(shortages);
        }

        // 락 획득 이후 시각 부여 (밀리초 절삭: timestamptz 반올림 불일치 방지)
        Order order = new Order(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));
        for (OrderItemRequest item : items) {
            Product p = products.get(item.productId());
            p.decreaseStock(item.quantity());
            order.addItem(p.getId(), item.quantity(), p.getPrice());
        }
        Order saved = orderRepository.save(order);
        return toResponse(saved);
    }

    public OrderResponse getById(long id) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        return toResponse(order);
    }

    @Transactional
    public OrderResponse cancel(long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> new OrderNotFoundException(id));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new OrderAlreadyCancelledException(id);
        }
        Map<Long, Integer> quantities = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            quantities.put(item.getProductId(), item.getQuantity());
        }
        List<Long> ids = quantities.keySet().stream().sorted().toList();
        for (Product p : productRepository.findAllByIdInForUpdate(ids)) {
            p.increaseStock(quantities.get(p.getId()));
        }
        order.cancel();
        return toResponse(order);
    }

    public PageResponse<OrderResponse> list(int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            // JPA setFirstResult(int) 오버플로 방지: 쿼리 없이 빈 content + count
            return new PageResponse<>(List.of(), page, size, orderRepository.count());
        }
        Page<Order> result = orderRepository.findAll(PageRequest.of(page, size, LIST_SORT));
        List<OrderResponse> content = result.getContent().stream().map(this::toResponse).toList();
        return new PageResponse<>(content, page, size, result.getTotalElements());
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(
                order.getId(), order.getStatus(), order.getTotalPrice(), items, order.getCreatedAt());
    }
}

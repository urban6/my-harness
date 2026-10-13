package com.example.order.order;

import com.example.order.common.web.PageResponse;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderItemRequest;
import com.example.order.order.dto.OrderResponse;
import com.example.order.product.InsufficientStockException;
import com.example.order.product.Product;
import com.example.order.product.ProductNotFoundException;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<OrderItemRequest> requested = request.items();

        // 1. 존재 확인 (읽기만). 하나라도 없으면 404 — 차감(409 판정)보다 먼저.
        List<Long> ids = requested.stream().map(OrderItemRequest::productId).toList();
        Map<Long, Product> products = new HashMap<>();
        for (Product p : productRepository.findAllById(ids)) {
            products.put(p.getId(), p);
        }
        List<Long> missing = ids.stream().filter(id -> !products.containsKey(id)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new ProductNotFoundException(missing);
        }

        // 2. productId 오름차순으로 조건부 차감 (데드락 방지). 영향 행 0이면 409 + 롤백.
        List<OrderItemRequest> sorted = requested.stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .toList();
        for (OrderItemRequest item : sorted) {
            if (productRepository.decreaseStock(item.productId(), item.quantity()) == 0) {
                throw new InsufficientStockException(item.productId(), item.quantity());
            }
        }

        // 3. 합계 계산 (오버플로 시 ArithmeticException -> 400) + 4. 저장 (요청 순서 유지)
        long total = 0;
        for (OrderItemRequest item : requested) {
            long unitPrice = products.get(item.productId()).getPrice();
            total = Math.addExact(total, Math.multiplyExact(unitPrice, (long) item.quantity()));
        }
        Order order = new Order(total);
        for (OrderItemRequest item : requested) {
            order.addItem(new OrderItem(item.productId(), item.quantity(),
                    products.get(item.productId()).getPrice()));
        }
        Order saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return orderRepository.findWithItemsById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new OrderNotFoundException(id);
        }
        if (orderRepository.cancelIfOrdered(id) == 0) {
            throw new OrderAlreadyCancelledException(id);
        }
        Order order = orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        List<OrderItem> items = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .toList();
        for (OrderItem item : items) {
            productRepository.increaseStock(item.getProductId(), item.getQuantity());
        }
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(int page, int size) {
        // offset(page * size)이 int 범위를 넘으면 JPA setFirstResult가 실패하므로 DB 조회 없이 빈 페이지를 돌려준다.
        if ((long) page * size > Integer.MAX_VALUE) {
            return new PageResponse<>(List.of(), page, size, orderRepository.count());
        }
        Page<Order> result = orderRepository.findAll(PageRequest.of(page, size, LIST_SORT));
        List<OrderResponse> content = result.getContent().stream().map(OrderResponse::from).toList();
        return new PageResponse<>(content, page, size, result.getTotalElements());
    }
}

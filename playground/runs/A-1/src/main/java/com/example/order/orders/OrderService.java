package com.example.order.orders;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.order.common.ApiException;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * 400(중복 상품) → 404(없는 상품) → 409(재고 부족) 순으로 검사한다.
     * 상품 행을 id 순으로 잠근 뒤 재고를 확인·차감하므로 동시 주문에도 재고가 음수가 되지 않으며,
     * 하나라도 실패하면 트랜잭션 전체가 롤백되어 재고는 전혀 차감되지 않는다.
     */
    @Transactional
    public OrderResponse create(OrderRequest request) {
        Set<Long> productIds = new HashSet<>();
        for (OrderRequest.Item item : request.items()) {
            if (!productIds.add(item.productId())) {
                throw ApiException.badRequest("Duplicate productId in items: " + item.productId());
            }
        }

        Map<Long, Product> products = productRepository.findAllByIdInForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Set<Long> missing = new TreeSet<>(productIds);
        missing.removeAll(products.keySet());
        if (!missing.isEmpty()) {
            throw ApiException.notFound("Products not found: " + missing);
        }

        for (OrderRequest.Item item : request.items()) {
            Product product = products.get(item.productId());
            if (!product.hasStock(item.quantity())) {
                throw ApiException.conflict("Insufficient stock for product " + product.getId()
                        + ": requested " + item.quantity() + ", available " + product.getStock() + ".");
            }
        }

        Order order = new Order(Instant.now());
        for (OrderRequest.Item item : request.items()) {
            Product product = products.get(item.productId());
            product.decreaseStock(item.quantity());
            order.addItem(product.getId(), item.quantity(), product.getPrice());
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> orderNotFound(id));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> orderNotFound(id));
        if (order.isCancelled()) {
            throw ApiException.conflict("Order " + id + " is already cancelled.");
        }
        order.cancel();

        Map<Long, Integer> quantities = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        for (Product product : productRepository.findAllByIdInForUpdate(quantities.keySet())) {
            product.increaseStock(quantities.get(product.getId()));
        }
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<Order> result = orderRepository.findAll(PageRequest.of(page, size, sort));
        List<OrderResponse> content = result.getContent().stream().map(OrderResponse::from).toList();
        return new PageResponse<>(content, page, size, result.getTotalElements());
    }

    private static ApiException orderNotFound(Long id) {
        return ApiException.notFound("Order " + id + " not found.");
    }
}

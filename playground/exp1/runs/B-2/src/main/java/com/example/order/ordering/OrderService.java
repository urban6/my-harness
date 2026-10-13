package com.example.order.ordering;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.order.product.Product;
import com.example.order.product.ProductNotFoundException;
import com.example.order.product.ProductRepository;

@Service
public class OrderService {

    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * 상품 행을 잠근 뒤 존재(404) → 재고(409) 순으로 확인하고 차감한다.
     * 하나라도 실패하면 트랜잭션이 롤백되어 재고는 전혀 차감되지 않는다.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Map<Long, Integer> quantities = request.items().stream()
                .collect(Collectors.toMap(CreateOrderRequest.Item::productId, CreateOrderRequest.Item::quantity,
                        (a, b) -> a, LinkedHashMap::new));
        Map<Long, Product> products = lockProducts(quantities);

        for (Long productId : quantities.keySet()) {
            if (!products.containsKey(productId)) {
                throw new ProductNotFoundException(productId);
            }
        }

        Order order = Order.place();
        quantities.forEach((productId, quantity) -> {
            Product product = products.get(productId);
            product.decreaseStock(quantity);
            order.addItem(productId, quantity, product.getPrice());
        });
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findWithLockById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        order.cancel();

        Map<Long, Integer> quantities = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        lockProducts(quantities).forEach((productId, product) ->
                product.increaseStock(quantities.get(productId)));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(int page, int size) {
        return PageResponse.from(
                orderRepository.findAll(PageRequest.of(page, size, LATEST_FIRST)).map(OrderResponse::from));
    }

    private Map<Long, Product> lockProducts(Map<Long, Integer> quantities) {
        return productRepository.findByIdInOrderByIdAsc(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }
}

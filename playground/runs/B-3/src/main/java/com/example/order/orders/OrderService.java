package com.example.order.orders;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.order.common.dto.PageResponse;
import com.example.order.common.error.InvalidRequestException;
import com.example.order.orders.dto.CreateOrderRequest;
import com.example.order.orders.dto.OrderItemRequest;
import com.example.order.orders.dto.OrderResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductNotFoundException;
import com.example.order.product.ProductRepository;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * 모든 항목의 재고가 충분할 때만 주문을 만든다. 상품 행을 잠근 뒤 검사·차감하므로
     * 동시 주문에서도 재고가 음수가 되지 않고, 실패 시 트랜잭션 롤백으로 차감이 전혀 남지 않는다.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<OrderItemRequest> items = request.items();
        Set<Long> productIds = new HashSet<>();
        for (OrderItemRequest item : items) {
            if (!productIds.add(item.productId())) {
                throw new InvalidRequestException("같은 상품을 중복해서 주문할 수 없습니다: productId=" + item.productId());
            }
        }

        Map<Long, Product> products = lockProducts(productIds);
        List<Long> missing = items.stream()
                .map(OrderItemRequest::productId)
                .filter(id -> !products.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new ProductNotFoundException(missing);
        }

        Order order = Order.place();
        for (OrderItemRequest item : items) {
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

    public PageResponse<OrderResponse> list(int page, int size) {
        return PageResponse.from(orderRepository.findAll(PageRequest.of(page, size, LATEST_FIRST))
                .map(OrderResponse::from));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        order.cancel();

        Map<Long, Product> products = lockProducts(
                order.getItems().stream().map(OrderItem::getProductId).collect(Collectors.toSet()));
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).increaseStock(item.getQuantity());
        }
        return OrderResponse.from(order);
    }

    private Map<Long, Product> lockProducts(Set<Long> ids) {
        return productRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }
}

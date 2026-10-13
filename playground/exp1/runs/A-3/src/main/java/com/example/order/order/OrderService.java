package com.example.order.order;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.order.common.ApiException;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderItemRequest;
import com.example.order.order.OrderDtos.OrderPageResponse;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.clock = Clock.systemUTC();
    }

    /**
     * Creates an order only if every item is in stock. Products are row-locked in id order,
     * so stock is either deducted for all items or for none, even under concurrent requests.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<OrderItemRequest> items = request.items();
        Set<Long> seen = new HashSet<>();
        for (OrderItemRequest item : items) {
            if (!seen.add(item.productId())) {
                throw ApiException.badRequest("Duplicate productId " + item.productId() + " in items.");
            }
        }

        Map<Long, Product> products = productRepository.findAllByIdInForUpdate(seen).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Set<Long> missing = new TreeSet<>(seen);
        missing.removeAll(products.keySet());
        if (!missing.isEmpty()) {
            throw ApiException.notFound("Product(s) not found: " + missing + ".");
        }

        for (OrderItemRequest item : items) {
            Product product = products.get(item.productId());
            if (!product.hasStock(item.quantity())) {
                throw ApiException.conflict("Insufficient stock for product " + product.getId()
                        + ": requested " + item.quantity() + ", available " + product.getStock() + ".");
            }
        }

        Order order = new Order(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        try {
            for (OrderItemRequest item : items) {
                Product product = products.get(item.productId());
                product.decreaseStock(item.quantity());
                order.addItem(product.getId(), item.quantity(), product.getPrice());
            }
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("Order total price is too large.");
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
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("Order " + id + " is already cancelled.");
        }

        Map<Long, Integer> quantities = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        for (Product product : productRepository.findAllByIdInForUpdate(quantities.keySet())) {
            product.increaseStock(quantities.get(product.getId()));
        }
        order.cancel();
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderPageResponse list(int page, int size) {
        if (page < 0) {
            throw ApiException.badRequest("page must be greater than or equal to 0.");
        }
        if (size < 1 || size > 100) {
            throw ApiException.badRequest("size must be between 1 and 100.");
        }
        return OrderPageResponse.from(orderRepository.findAll(PageRequest.of(page, size, NEWEST_FIRST)));
    }

    private static ApiException orderNotFound(Long id) {
        return ApiException.notFound("Order " + id + " not found.");
    }
}

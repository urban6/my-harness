package com.example.order.order;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.order.common.BadRequestException;
import com.example.order.common.ConflictException;
import com.example.order.common.NotFoundException;
import com.example.order.common.PageResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * Creates an order and deducts stock for every item, all or nothing.
     * Errors are checked in the order 400 (duplicates, price overflow) -> 404 (missing product) -> 409 (stock).
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<CreateOrderRequest.Item> items = request.items();
        Set<Long> productIds = new HashSet<>();
        for (CreateOrderRequest.Item item : items) {
            if (!productIds.add(item.productId())) {
                throw new BadRequestException("Duplicate productId " + item.productId() + " in items.");
            }
        }

        Map<Long, Product> products = productRepository.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Set<Long> missing = new TreeSet<>(productIds);
        missing.removeAll(products.keySet());
        if (!missing.isEmpty()) {
            throw new NotFoundException("Products not found: " + missing + ".");
        }

        long totalPrice = 0;
        try {
            for (CreateOrderRequest.Item item : items) {
                long linePrice = Math.multiplyExact(products.get(item.productId()).getPrice(), (long) item.quantity());
                totalPrice = Math.addExact(totalPrice, linePrice);
            }
        } catch (ArithmeticException e) {
            throw new BadRequestException("Total price is too large.");
        }

        for (CreateOrderRequest.Item item : items) {
            Product product = products.get(item.productId());
            if (!product.hasStock(item.quantity())) {
                throw new ConflictException("Insufficient stock for product " + product.getId()
                        + ": requested " + item.quantity() + ", available " + product.getStock() + ".");
            }
        }

        Order order = new Order(totalPrice);
        for (CreateOrderRequest.Item item : items) {
            Product product = products.get(item.productId());
            product.decreaseStock(item.quantity());
            order.addItem(product.getId(), item.quantity(), product.getPrice());
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return OrderResponse.from(findOrder(id));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> orderNotFound(id));
        if (order.isCancelled()) {
            throw new ConflictException("Order " + id + " is already cancelled.");
        }

        Map<Long, Integer> quantities = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        for (Product product : productRepository.findAllByIdForUpdate(quantities.keySet())) {
            product.increaseStock(quantities.get(product.getId()));
        }
        order.cancel();
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(int page, int size) {
        Page<Order> orders = orderRepository.findAll(PageRequest.of(page, size, NEWEST_FIRST));
        List<OrderResponse> content = orders.getContent().stream().map(OrderResponse::from).toList();
        return new PageResponse<>(content, page, size, orders.getTotalElements());
    }

    private Order findOrder(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> orderNotFound(id));
    }

    private static NotFoundException orderNotFound(Long id) {
        return new NotFoundException("Order " + id + " not found.");
    }
}

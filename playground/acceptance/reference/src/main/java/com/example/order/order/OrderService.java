package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    public record Line(Long productId, int quantity) {
    }

    private final OrderRepository orders;
    private final ProductRepository products;

    public OrderService(OrderRepository orders, ProductRepository products) {
        this.orders = orders;
        this.products = products;
    }

    @Transactional
    public Order place(List<Line> lines) {
        Set<Long> ids = new HashSet<>();
        for (Line line : lines) {
            if (!ids.add(line.productId())) {
                throw ApiException.badRequest("duplicate productId " + line.productId());
            }
        }
        Map<Long, Product> locked = products.findAllForUpdate(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Line line : lines) {
            if (!locked.containsKey(line.productId())) {
                throw ApiException.notFound("product " + line.productId() + " not found");
            }
        }
        for (Line line : lines) {
            if (!locked.get(line.productId()).hasStock(line.quantity())) {
                throw ApiException.conflict("insufficient stock for product " + line.productId());
            }
        }
        Order order = new Order(Instant.now());
        for (Line line : lines) {
            Product product = locked.get(line.productId());
            product.decrease(line.quantity());
            order.addItem(product.getId(), line.quantity(), product.getPrice());
        }
        return orders.save(order);
    }

    @Transactional(readOnly = true)
    public Order get(Long id) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("order " + id + " not found"));
        order.getItems().size();
        return order;
    }

    @Transactional
    public Order cancel(Long id) {
        Order order = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("order " + id + " not found"));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("order " + id + " already cancelled");
        }
        Set<Long> ids = order.getItems().stream().map(OrderItem::getProductId).collect(Collectors.toSet());
        Map<Long, Product> locked = products.findAllForUpdate(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (OrderItem item : order.getItems()) {
            locked.get(item.getProductId()).increase(item.getQuantity());
        }
        order.cancel();
        return order;
    }

    @Transactional(readOnly = true)
    public Page<Order> list(int page, int size) {
        Page<Order> result = orders.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size));
        result.forEach(o -> o.getItems().size());
        return result;
    }
}

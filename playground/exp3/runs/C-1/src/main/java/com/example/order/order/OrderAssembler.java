package com.example.order.order;

import com.example.order.common.Problems;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds API order representations; batches the item lookup for lists (no N+1). */
@Component
public class OrderAssembler {

    private final OrderRepository orders;

    public OrderAssembler(OrderRepository orders) {
        this.orders = orders;
    }

    public OrderResponse load(long orderId) {
        OrderRow row = orders.findById(orderId).orElseThrow(() -> Problems.orderNotFound(orderId));
        return assemble(row);
    }

    public OrderResponse assemble(OrderRow row) {
        return OrderResponse.from(row, orders.findItems(List.of(row.id())));
    }

    public List<OrderResponse> assemble(List<OrderRow> rows) {
        Map<Long, List<OrderItemRow>> itemsByOrder = orders.findItems(rows.stream().map(OrderRow::id).toList())
                .stream().collect(Collectors.groupingBy(OrderItemRow::orderId));
        return rows.stream().map(r -> OrderResponse.from(r, itemsByOrder.getOrDefault(r.id(), List.of()))).toList();
    }
}

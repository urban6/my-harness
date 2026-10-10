package com.example.order.order;

import com.example.order.common.Problems;
import com.example.order.common.TimeSupport;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** GET /api/orders/{id} and GET /api/orders (cursor pagination, id DESC). Lazy-expires before reading. */
@Service
public class OrderQueryService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    static final int SWEEP_BATCH = 100;
    private static final String CURSOR_PREFIX = "o:";

    private final PurchaseOrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderExpiryService expiryService;
    private final Clock clock;
    private final TransactionTemplate readTx;

    @PersistenceContext
    private EntityManager entityManager;

    public OrderQueryService(PurchaseOrderRepository orderRepository, OrderItemRepository itemRepository,
            OrderExpiryService expiryService, Clock clock, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.expiryService = expiryService;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public OrderResponse get(long id) {
        expiryService.expireIfDue(id);
        return readTx.execute(status -> {
            PurchaseOrder order = orderRepository.findById(id).orElseThrow(() -> Problems.orderNotFound(id));
            return OrderResponse.of(order, itemRepository.findByOrderIdOrderByIdAsc(id));
        });
    }

    public OrderPage list(String userId, String statusParam, String sizeParam, String cursorParam) {
        String filterUser = parseUserId(userId);
        OrderStatus filterStatus = parseStatus(statusParam);
        int size = parseSize(sizeParam);
        Long cursorId = parseCursor(cursorParam);

        expiryService.expireDueOrders(TimeSupport.now(clock), SWEEP_BATCH);

        return readTx.execute(tx -> {
            StringBuilder jpql = new StringBuilder("select o from PurchaseOrder o where 1 = 1");
            if (filterUser != null) {
                jpql.append(" and o.userId = :userId");
            }
            if (filterStatus != null) {
                jpql.append(" and o.status = :status");
            }
            if (cursorId != null) {
                jpql.append(" and o.id < :cursorId");
            }
            jpql.append(" order by o.id desc");
            TypedQuery<PurchaseOrder> query = entityManager.createQuery(jpql.toString(), PurchaseOrder.class);
            if (filterUser != null) {
                query.setParameter("userId", filterUser);
            }
            if (filterStatus != null) {
                query.setParameter("status", filterStatus);
            }
            if (cursorId != null) {
                query.setParameter("cursorId", cursorId);
            }
            query.setMaxResults(size + 1);
            List<PurchaseOrder> rows = new ArrayList<>(query.getResultList());
            boolean hasNext = rows.size() > size;
            List<PurchaseOrder> page = hasNext ? rows.subList(0, size) : rows;

            Map<Long, List<OrderItem>> itemsByOrder = page.isEmpty() ? Map.of()
                    : itemRepository.findByOrderIdInOrderByIdAsc(page.stream().map(PurchaseOrder::getId).toList())
                            .stream().collect(Collectors.groupingBy(OrderItem::getOrderId));
            List<OrderResponse> content = page.stream()
                    .map(o -> OrderResponse.of(o, itemsByOrder.getOrDefault(o.getId(), List.of()))).toList();
            String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).getId()) : null;
            return new OrderPage(content, nextCursor);
        });
    }

    private static String parseUserId(String userId) {
        if (userId == null) {
            return null;
        }
        if (userId.isEmpty() || userId.length() > 64) {
            throw Problems.invalidParameter("userId", "Parameter 'userId' must be 1-64 characters.");
        }
        return userId;
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw Problems.invalidParameter("status", "Parameter 'status' is not a valid order status.");
        }
    }

    private static int parseSize(String size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(size);
        } catch (NumberFormatException e) {
            throw Problems.invalidParameter("size", "Parameter 'size' must be an integer between 1 and " + MAX_SIZE + ".");
        }
        if (parsed < 1 || parsed > MAX_SIZE) {
            throw Problems.invalidParameter("size", "Parameter 'size' must be an integer between 1 and " + MAX_SIZE + ".");
        }
        return parsed;
    }

    static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((CURSOR_PREFIX + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(CURSOR_PREFIX)) {
                throw new IllegalArgumentException("prefix");
            }
            long id = Long.parseLong(decoded.substring(CURSOR_PREFIX.length()));
            if (id <= 0) {
                throw new IllegalArgumentException("id");
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw Problems.invalidParameter("cursor", "Parameter 'cursor' is not a valid cursor.");
        }
    }
}

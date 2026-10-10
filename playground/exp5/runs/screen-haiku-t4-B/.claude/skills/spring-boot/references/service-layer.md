# 서비스 레이어 — 로직 · 트랜잭션 · 매핑

## 원칙

- 비즈니스 로직·트랜잭션 경계·DTO↔엔티티 매핑이 여기 산다.
- 생성자 주입, `final` 의존성.
- 트랜잭션은 **서비스 메서드**에서. 읽기 전용은 `readOnly = true`.
- 없는 리소스·규칙 위반은 **도메인 예외**를 던진다(HTTP를 알지 못함 — 매핑은 예외 핸들러가).

## 서비스 템플릿

```java
@Service
@Transactional(readOnly = true)          // 클래스 기본: 읽기 전용
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional                        // 쓰기 메서드만 오버라이드
    public OrderResponse create(CreateOrderRequest request) {
        Order order = new Order(
                request.customerName(),
                request.quantity(),
                request.unitPrice()
        );
        Order saved = orderRepository.save(order);
        return toResponse(saved);
    }

    public OrderResponse getById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));   // 도메인 예외
        return toResponse(order);
    }

    public Page<OrderResponse> list(Pageable pageable) {
        return orderRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional
    public void delete(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new OrderNotFoundException(id);
        }
        orderRepository.deleteById(id);
    }

    // 매핑은 서비스에 두거나 별도 매퍼로 분리. 엔티티→DTO 변환 지점을 일관되게.
    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerName(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getCreatedAt()
        );
    }
}
```

## 트랜잭션 주의

- `@Transactional`은 **프록시 기반** — 같은 클래스 내 메서드 자기호출(self-invocation)에는 적용되지 않는다.
- `public` 메서드에만 적용된다.
- 트랜잭션 안에서 외부 API 호출·오래 걸리는 작업을 피한다(커넥션 점유).
- 조회 전용 흐름에 `readOnly = true`를 주면 플러시 최적화·명확성 이점이 있다.
- 체크 예외는 기본적으로 롤백하지 않는다. 필요하면 `@Transactional(rollbackFor = ...)`.

## 매핑

- 엔티티↔DTO 변환은 **서비스 경계**에서 한다(컨트롤러·리포지토리 아님).
- 프로젝트에 MapStruct 등 매퍼 관례가 있으면 그것을 따른다. 없으면 위처럼 명시 매핑 메서드로 충분하다.
- 도메인 로직(합계 계산 등)은 DTO가 아니라 **엔티티/도메인 객체**에 둔다.

## 체크리스트

- [ ] 생성자 주입, `final` 필드인가?
- [ ] 쓰기 메서드에 `@Transactional`, 읽기에 `readOnly = true`인가?
- [ ] 없는 리소스/규칙 위반에 도메인 예외를 던지는가(HTTP 상태코드를 서비스가 알지 않음)?
- [ ] 엔티티가 서비스 경계를 넘어 컨트롤러로 새지 않는가(DTO로 변환)?

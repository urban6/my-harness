# 웹 레이어 — 컨트롤러 · DTO · 검증 · 상태코드

## 원칙

- `@RestController`로 얇게 유지 — HTTP 매핑·검증·응답 변환만. 비즈니스 로직은 서비스로 위임.
- 요청/응답은 **record DTO**. JPA 엔티티를 직접 받거나 반환하지 않는다.
- 상태코드·`Location` 헤더는 `api_contract`(api-designer 산출물)에 정확히 맞춘다.

## 컨트롤러 템플릿

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // 생성: 201 + Location
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    // 단건 조회: 200 / 404
    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.getById(id);   // 없으면 서비스가 도메인 예외 → 404 매핑
    }

    // 목록 조회: 200 + 페이지네이션
    @GetMapping
    public Page<OrderResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return orderService.list(pageable);
    }

    // 삭제: 204
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        orderService.delete(id);
    }
}
```

## 요청/응답 DTO (record + Bean Validation)

```java
// 요청 — jakarta.validation 으로 입력 검증
public record CreateOrderRequest(
        @NotBlank String customerName,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin("0.0") BigDecimal unitPrice
) {}

// 응답 — 노출할 필드만. 엔티티를 그대로 내보내지 않는다.
public record OrderResponse(
        Long id,
        String customerName,
        int quantity,
        BigDecimal totalPrice,
        Instant createdAt
) {}
```

- 컨트롤러 파라미터에 `@Valid`를 붙여야 검증이 동작한다. 검증 실패는 `MethodArgumentNotValidException` → 예외 핸들러가 400으로 매핑(`exception-handling.md`).
- 중첩 객체 검증은 필드에 `@Valid`를 추가한다.

## 상태코드 매핑 (기본값 — api_contract 우선)

| 상황 | 코드 | 비고 |
| --- | --- | --- |
| 생성 성공 | `201 Created` | `Location` 헤더 필수 |
| 조회·수정 성공(바디 있음) | `200 OK` | |
| 성공(바디 없음: 삭제 등) | `204 No Content` | |
| 검증 실패 | `400 Bad Request` | ProblemDetail |
| 인증 없음/실패 | `401 Unauthorized` | |
| 권한 없음 | `403 Forbidden` | |
| 리소스 없음 | `404 Not Found` | ProblemDetail |
| 상태 충돌(중복 등) | `409 Conflict` | |
| 서버 오류 | `500` | 스택트레이스 노출 금지 |

## 안티패턴(피할 것)

- 컨트롤러에서 리포지토리 직접 호출 → 반드시 서비스 경유.
- 엔티티를 `@RequestBody`/응답으로 노출 → DTO 사용.
- 컨트롤러에 `@Transactional` → 트랜잭션은 서비스에서.
- 비즈니스 예외를 컨트롤러에서 try/catch로 상태코드 분기 → 전역 예외 핸들러로 통일.

## 체크리스트

- [ ] 요청/응답이 record DTO인가? 엔티티 노출이 없는가?
- [ ] `@Valid`가 붙어 있고 제약 애노테이션이 계약과 일치하는가?
- [ ] 상태코드·`Location`이 `api_contract`와 일치하는가?
- [ ] 컨트롤러가 얇은가(로직·트랜잭션·리포지토리 접근 없음)?

# 예외 처리 — RFC 9457 ProblemDetail

에러 응답은 **RFC 9457**(`application/problem+json`)로 통일한다. `api-designer`의 기본 에러 포맷과 정렬된다. Spring 6/Boot 3는 `ProblemDetail`을 기본 제공한다.

## 도메인 예외

HTTP를 모르는 순수 도메인 예외를 정의하고, 매핑은 핸들러에서 한다.

```java
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long id) {
        super("주문을 찾을 수 없습니다: id=" + id);
    }
}

public class DuplicateOrderException extends RuntimeException {
    public DuplicateOrderException(String key) {
        super("이미 존재하는 주문입니다: " + key);
    }
}
```

## 전역 예외 핸들러

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 404
    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Resource Not Found");
        pd.setType(URI.create("https://example.com/problems/not-found"));
        return pd;
    }

    // 409
    @ExceptionHandler(DuplicateOrderException.class)
    public ProblemDetail handleConflict(DuplicateOrderException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Conflict");
        return pd;
    }

    // 400 — Bean Validation 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "요청 검증에 실패했습니다.");
        pd.setTitle("Validation Failed");
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        pd.setProperty("errors", errors);        // 확장 필드
        return pd;
    }

    // 500 — 최후의 방어. 내부 메시지·스택트레이스를 노출하지 않는다.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // 서버 로그에는 상세를, 응답에는 일반 메시지만.
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "예상치 못한 오류가 발생했습니다.");
    }
}
```

응답 예:
```json
{
  "type": "https://example.com/problems/not-found",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "주문을 찾을 수 없습니다: id=42"
}
```

## 원칙

- **상태코드 매핑은 이 핸들러 한 곳에.** 컨트롤러/서비스에서 try/catch로 상태코드를 분기하지 않는다.
- **내부 정보 은닉.** 500 응답에 예외 메시지·스택트레이스·SQL을 넣지 않는다. 상세는 로그로.
- **검증 실패는 필드별 오류**를 `errors` 확장 필드로 제공하면 클라이언트 UX가 좋아진다.
- `type` URI는 프로젝트 도메인에 맞춘 안정적 식별자로. 없으면 생략 가능(`about:blank`).
- 스프링 기본 예외(`ResponseStatusException`, `ErrorResponseException`)를 활용해도 된다 — 프로젝트 관례를 따른다.

## 체크리스트

- [ ] 에러 응답이 `ProblemDetail`(RFC 9457) 형태인가?
- [ ] 도메인 예외가 HTTP를 모르고, 매핑이 핸들러에 모여 있는가?
- [ ] 검증 실패(400)에 필드별 오류가 있는가?
- [ ] 500 응답에 내부 상세가 새지 않는가?

# 예외 처리 — 예외 필터 · RFC 9457

에러 응답은 **RFC 9457**(`application/problem+json`)로 통일한다. `api-designer`의 기본 에러 포맷과 정렬된다. NestJS 기본 에러 바디(`{ statusCode, message, error }`) 대신 전역 예외 필터로 ProblemDetail 형태를 만든다.

## 예외 던지기

NestJS 내장 HTTP 예외를 서비스에서 던지면 상태코드가 자연스럽게 매핑된다.

```ts
throw new NotFoundException(`주문을 찾을 수 없습니다: id=${id}`);   // 404
throw new ConflictException('이미 존재하는 주문입니다.');           // 409
throw new BadRequestException('잘못된 요청입니다.');                // 400
```

HTTP를 모르는 순수 도메인 예외를 쓰고 싶으면 별도 클래스를 만들고 필터에서 상태코드로 매핑한다.

## 전역 예외 필터 → ProblemDetail

```ts
// common/filters/problem-detail.filter.ts
@Catch()
export class ProblemDetailFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const res = ctx.getResponse<Response>();
    const req = ctx.getRequest<Request>();

    const isHttp = exception instanceof HttpException;
    const status = isHttp
      ? exception.getStatus()
      : HttpStatus.INTERNAL_SERVER_ERROR;

    // HttpException 응답 본문에서 메시지/검증 오류 추출
    const payload = isHttp ? exception.getResponse() : null;
    const detail =
      typeof payload === 'string'
        ? payload
        : (payload as any)?.message ?? '예상치 못한 오류가 발생했습니다.';

    const problem: Record<string, unknown> = {
      type: 'about:blank',
      title: isHttp ? exception.name : 'Internal Server Error',
      status,
      detail: Array.isArray(detail) ? '요청 검증에 실패했습니다.' : detail,
      instance: req.url,
    };

    // 검증 실패(400): 필드 오류 배열을 확장 필드로
    if (Array.isArray(detail)) {
      problem.errors = detail;
    }

    // 500은 내부 상세를 노출하지 않는다 (로깅은 별도)
    if (status >= 500) {
      problem.detail = '예상치 못한 오류가 발생했습니다.';
    }

    res.status(status).type('application/problem+json').json(problem);
  }
}
```

`main.ts`에서 `app.useGlobalFilters(new ProblemDetailFilter())`로 등록한다(`project-layout.md`).

응답 예:
```json
{
  "type": "about:blank",
  "title": "NotFoundException",
  "status": 404,
  "detail": "주문을 찾을 수 없습니다: id=42",
  "instance": "/orders/42"
}
```

## 원칙

- **상태코드 매핑은 한 곳(필터)에.** 컨트롤러/서비스에서 try/catch로 상태코드를 분기하지 않는다.
- **내부 정보 은닉.** 500 응답에 예외 메시지·스택트레이스·SQL을 넣지 않는다. 상세는 서버 로그로.
- **검증 실패는 필드별 오류**를 `errors` 확장 필드로. `ValidationPipe`가 만든 메시지 배열을 담는다.
- `type` URI는 프로젝트 도메인의 안정적 식별자로 두면 좋다(없으면 `about:blank`).

## 체크리스트

- [ ] 에러 응답이 `application/problem+json`(RFC 9457) 형태인가?
- [ ] 상태코드 매핑이 전역 필터에 모여 있는가?
- [ ] 검증 실패(400)에 필드별 오류가 있는가?
- [ ] 500 응답에 내부 상세가 새지 않는가?

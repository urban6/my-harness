# 웹 레이어 — 컨트롤러 · DTO · 검증 · 상태코드

## 원칙

- `@Controller`는 얇게 — 라우팅·검증·응답 변환만. 비즈니스 로직은 서비스로 위임.
- 요청/응답은 **DTO 클래스**. Prisma 모델을 직접 받거나 반환하지 않는다.
- 상태코드는 `api_contract`(api-designer 산출물)에 맞춘다. NestJS는 기본 200(POST는 201)을 주므로 필요 시 `@HttpCode`로 명시.

## 컨트롤러 템플릿

```ts
@Controller('orders')
export class OrderController {
  constructor(private readonly orderService: OrderService) {}

  // 생성: 201 (POST 기본값) + Location
  @Post()
  async create(
    @Body() dto: CreateOrderDto,
    @Res({ passthrough: true }) res: Response,
  ): Promise<OrderResponseDto> {
    const created = await this.orderService.create(dto);
    res.location(`/orders/${created.id}`);
    return created;
  }

  // 단건 조회: 200 / 404
  @Get(':id')
  get(@Param('id', ParseIntPipe) id: number): Promise<OrderResponseDto> {
    return this.orderService.getById(id); // 없으면 서비스가 도메인 예외 → 404
  }

  // 목록: 200 + 페이지네이션
  @Get()
  list(@Query() query: ListOrderQueryDto): Promise<PageDto<OrderResponseDto>> {
    return this.orderService.list(query);
  }

  // 삭제: 204
  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(@Param('id', ParseIntPipe) id: number): Promise<void> {
    return this.orderService.remove(id);
  }
}
```

## 요청 DTO (class-validator)

```ts
// create-order.dto.ts
import { IsInt, IsNotEmpty, IsPositive, IsString, Min } from 'class-validator';
import { Type } from 'class-transformer';

export class CreateOrderDto {
  @IsString()
  @IsNotEmpty()
  customerName: string;

  @Type(() => Number)     // transform: true 와 함께 문자열→숫자 변환
  @IsInt()
  @IsPositive()
  quantity: number;

  @Type(() => Number)
  @Min(0)
  unitPrice: number;
}
```

- 전역 `ValidationPipe({ whitelist: true, transform: true })`가 있어야 DTO 밖 필드 제거·타입 변환·검증이 동작한다(`project-layout.md`의 `main.ts`).
- 쿼리·파라미터도 DTO/파이프로 검증(`ParseIntPipe` 등).

## 응답 DTO

```ts
export class OrderResponseDto {
  id: number;
  customerName: string;
  quantity: number;
  totalPrice: number;
  createdAt: string;      // ISO 8601
}
```

- Prisma 모델을 그대로 반환하지 않는다 — 노출 필드만 담은 DTO로 매핑(서비스에서, `service-layer.md`).
- 민감 필드(비밀번호 해시 등) 누출 방지. 필요하면 `ClassSerializerInterceptor` + `@Exclude`.

## 상태코드 매핑 (기본값 — api_contract 우선)

| 상황 | 코드 | NestJS |
| --- | --- | --- |
| 생성 성공 | `201` | POST 기본값. `Location` 헤더 추가 |
| 조회·수정 성공 | `200` | 기본값 |
| 성공(바디 없음) | `204` | `@HttpCode(204)` |
| 검증 실패 | `400` | ValidationPipe 자동 |
| 인증 없음 | `401` | 가드/필터 |
| 권한 없음 | `403` | 가드/필터 |
| 리소스 없음 | `404` | 도메인 예외 매핑 |
| 상태 충돌 | `409` | 도메인 예외 매핑 |
| 서버 오류 | `500` | 내부 상세 노출 금지 |

## 안티패턴(피할 것)

- 컨트롤러에서 `PrismaService`(리포지토리) 직접 호출 → 반드시 서비스 경유.
- Prisma 모델을 `@Body`/응답으로 노출 → DTO 사용.
- 컨트롤러에서 try/catch로 상태코드 분기 → 예외 필터로 통일(`exception-handling.md`).
- 전역 `ValidationPipe` 없이 수동 검증 산발.

## 체크리스트

- [ ] 요청/응답이 DTO 클래스인가? Prisma 모델 노출이 없는가?
- [ ] DTO에 class-validator 제약이 계약과 일치하는가?
- [ ] 상태코드·`Location`이 `api_contract`와 일치하는가(`@HttpCode` 명시)?
- [ ] 컨트롤러가 얇은가(로직·리포지토리 접근 없음)?

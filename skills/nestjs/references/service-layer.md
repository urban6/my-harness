# 서비스 레이어 — 프로바이더 · DI · 트랜잭션 · 매핑

## 원칙

- 비즈니스 로직·트랜잭션 경계·모델↔DTO 매핑이 여기 산다.
- `@Injectable()` 프로바이더, 생성자 주입.
- 없는 리소스·규칙 위반은 **예외를 던진다**(NestJS `HttpException` 계열 또는 도메인 예외 → 필터에서 매핑).
- 컨트롤러가 아니라 서비스가 `PrismaService`를 사용한다.

## 서비스 템플릿

```ts
@Injectable()
export class OrderService {
  constructor(private readonly prisma: PrismaService) {}

  async create(dto: CreateOrderDto): Promise<OrderResponseDto> {
    const created = await this.prisma.order.create({
      data: {
        customerName: dto.customerName,
        quantity: dto.quantity,
        unitPrice: dto.unitPrice,
      },
    });
    return this.toResponse(created);
  }

  async getById(id: number): Promise<OrderResponseDto> {
    const order = await this.prisma.order.findUnique({ where: { id } });
    if (!order) {
      throw new NotFoundException(`주문을 찾을 수 없습니다: id=${id}`);
    }
    return this.toResponse(order);
  }

  async list(query: ListOrderQueryDto): Promise<PageDto<OrderResponseDto>> {
    const { page = 1, size = 20 } = query;
    const [items, total] = await this.prisma.$transaction([
      this.prisma.order.findMany({ skip: (page - 1) * size, take: size }),
      this.prisma.order.count(),
    ]);
    return { items: items.map((o) => this.toResponse(o)), total, page, size };
  }

  async remove(id: number): Promise<void> {
    try {
      await this.prisma.order.delete({ where: { id } });
    } catch {
      throw new NotFoundException(`주문을 찾을 수 없습니다: id=${id}`);
    }
  }

  // 매핑은 서비스 경계에서. Prisma 모델 → 응답 DTO.
  private toResponse(o: Order): OrderResponseDto {
    return {
      id: o.id,
      customerName: o.customerName,
      quantity: o.quantity,
      totalPrice: o.unitPrice * o.quantity,
      createdAt: o.createdAt.toISOString(),
    };
  }
}
```

## 트랜잭션 (Prisma)

여러 쓰기를 원자적으로 묶어야 하면 `$transaction`을 쓴다.

- **순차 배열**: 독립 연산 묶음 — `await prisma.$transaction([op1, op2])`.
- **인터랙티브(콜백)**: 중간 결과에 따라 분기·읽기가 필요할 때:
  ```ts
  await this.prisma.$transaction(async (tx) => {
    const order = await tx.order.create({ data: {...} });
    await tx.inventory.update({ where: {...}, data: {...} });
    return order;
  });
  ```
- 트랜잭션 콜백 안에서는 반드시 `tx`를 쓴다(바깥 `this.prisma` 아님). 트랜잭션 안에서 외부 API 호출·장시간 작업을 피한다.
- 여러 리포지토리성 로직을 조합할 때 트랜잭션 경계는 **서비스**가 정한다.

## 매핑

- Prisma 모델↔DTO 변환은 **서비스 경계**에서(컨트롤러·리포지토리 아님).
- 도메인 계산(합계 등)은 매핑 시점 또는 도메인 함수로 일관되게.
- 프로젝트에 매퍼/직렬화 관례가 있으면 그것을 따른다.

## 체크리스트

- [ ] `@Injectable()` + 생성자 주입인가?
- [ ] 서비스만 `PrismaService`를 쓰는가(컨트롤러 직접 접근 없음)?
- [ ] 다중 쓰기를 `$transaction`으로 원자화했고, 콜백에서 `tx`를 쓰는가?
- [ ] 없는 리소스/규칙 위반에 예외를 던지는가?
- [ ] Prisma 모델이 서비스 경계를 넘지 않고 DTO로 변환되는가?

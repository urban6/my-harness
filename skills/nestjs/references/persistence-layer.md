# 영속성 레이어 — Prisma

## PrismaService

`PrismaClient`를 확장한 프로바이더를 전역 모듈로 제공한다.

```ts
// prisma/prisma.service.ts
@Injectable()
export class PrismaService extends PrismaClient implements OnModuleInit {
  async onModuleInit() {
    await this.$connect();
  }
}
```

```ts
// prisma/prisma.module.ts
@Global()
@Module({
  providers: [PrismaService],
  exports: [PrismaService],
})
export class PrismaModule {}
```

- `@Global()`로 어느 모듈에서든 주입 가능하게 한다. 종료 훅은 Nest의 `enableShutdownHooks`(main.ts) 또는 `OnModuleDestroy`에서 `$disconnect`.

## 스키마 (schema.prisma)

`03_db_design.md`(db-migrator 산출물)의 스키마·제약·인덱스를 여기에 정확히 옮긴다.

```prisma
generator client {
  provider = "prisma-client-js"
}

datasource db {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

model Order {
  id           Int      @id @default(autoincrement())
  customerName String
  quantity     Int
  unitPrice    Decimal  @db.Decimal(12, 2)
  createdAt    DateTime @default(now())

  @@map("orders")
  @@index([customerName])
}
```

- 스키마 변경 → `npx prisma generate`(클라이언트 재생성) 필수.
- `Decimal`은 클라이언트에서 `Prisma.Decimal`로 온다 — 금액 계산 시 형 변환 주의.

## 마이그레이션

- 개발: `npx prisma migrate dev --name <설명>` — 마이그레이션 생성+적용.
- 배포: `npx prisma migrate deploy` — 생성된 마이그레이션만 적용(운영에서 스키마를 임의로 밀지 않는다).
- `db push`는 프로토타이핑 한정. 운영 스키마는 마이그레이션으로 버전 관리.

## 쿼리 · 페이지네이션

```ts
// 페이지네이션: skip/take + 카운트를 한 트랜잭션으로
const [items, total] = await this.prisma.$transaction([
  this.prisma.order.findMany({
    where: { customerName },
    skip: (page - 1) * size,
    take: size,
    orderBy: { createdAt: 'desc' },
  }),
  this.prisma.order.count({ where: { customerName } }),
]);
```

- 정렬 필드는 사용자 입력을 그대로 신뢰하지 말고 허용 목록으로 검증.
- 큰 오프셋 페이지네이션이 느리면 커서 기반(`cursor`, `take`) 고려.

## N+1 회피 · 관계 로딩

- 관계는 기본적으로 로딩되지 않는다. 필요하면 `include` 또는 `select`로 **한 번에** 가져온다:
  ```ts
  this.prisma.order.findMany({ include: { items: true } });
  ```
- 반복문 안에서 관계를 개별 조회하지 않는다(N+1). `include`/`in` 배치 조회로 대체.
- 필요한 컬럼만 `select`로 좁히면 페이로드·성능에 유리.

## 체크리스트

- [ ] `schema.prisma`가 `03_db_design.md`의 스키마·제약·인덱스와 일치하는가?
- [ ] 스키마 변경 후 `prisma generate`를 돌렸는가?
- [ ] 마이그레이션으로 스키마를 버전 관리하는가(운영에서 `db push` 아님)?
- [ ] 목록이 페이지네이션되고 카운트가 같은 트랜잭션인가?
- [ ] 관계 로딩이 `include`/배치로 N+1을 피하는가?

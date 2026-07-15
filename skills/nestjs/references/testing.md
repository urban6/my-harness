# 테스트 — Jest 단위 · e2e

> **작성 주체는 `test-writer` 에이전트다.** 이 문서는 backend-impl이 (1) 기존 테스트를 실행해 회귀를 확인하고, (2) 프로젝트의 테스트 관례를 이해하도록 돕는 **참조**다. 새 테스트 작성은 `test-writer`에 위임한다.

## 테스트 계층 선택

| 대상 | 방식 | 범위 | 언제 |
| --- | --- | --- | --- |
| 서비스 로직 | Jest 단위 + 의존성 목 | 컨텍스트 없음 | 비즈니스 규칙 |
| 컨트롤러 | `Test.createTestingModule` + 서비스 목 | 좁은 모듈 | 라우팅·검증·응답 |
| 전체 흐름 | e2e + supertest | 앱 부팅 | 엔드투엔드 |

빠른 단위 테스트를 기본으로, e2e는 핵심 흐름에만.

## 단위 테스트 — TestingModule + 목

```ts
describe('OrderService', () => {
  let service: OrderService;
  const prisma = {
    order: {
      create: jest.fn(),
      findUnique: jest.fn(),
    },
  };

  beforeEach(async () => {
    const moduleRef = await Test.createTestingModule({
      providers: [
        OrderService,
        { provide: PrismaService, useValue: prisma },
      ],
    }).compile();

    service = moduleRef.get(OrderService);
  });

  it('getById가 없으면 NotFoundException을 던진다', async () => {
    prisma.order.findUnique.mockResolvedValue(null);

    await expect(service.getById(42)).rejects.toBeInstanceOf(NotFoundException);
  });
});
```

## e2e 테스트 — supertest

```ts
describe('Orders (e2e)', () => {
  let app: INestApplication;

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();

    app = moduleRef.createNestApplication();
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('POST /orders → 201', () => {
    return request(app.getHttpServer())
      .post('/orders')
      .send({ customerName: 'kim', quantity: 2, unitPrice: 10 })
      .expect(201)
      .expect((res) => expect(res.body.id).toBeDefined());
  });

  it('POST /orders 검증 실패 → 400', () => {
    return request(app.getHttpServer())
      .post('/orders')
      .send({ customerName: '', quantity: -1 })
      .expect(400);
  });
});
```

## 테스트 DB (Prisma)

- e2e·리포지토리 테스트는 **실제 DB**로 검증하는 게 신뢰도가 높다. 별도 테스트 DB(`.env.test`의 `DATABASE_URL`)를 쓰고, 각 테스트 전후로 정리.
- **Testcontainers**(`@testcontainers/postgresql`)로 격리된 Postgres를 띄우면 CI에서도 재현 가능:
  - 컨테이너 URL을 `DATABASE_URL`로 주입 → `prisma migrate deploy`로 스키마 적용 → 테스트.
- 목만으로는 Prisma 쿼리·제약을 검증하지 못하므로, 쿼리 정확성은 실 DB 테스트로.

## 규약

- **given-when-then** 구조, 테스트명은 행동을 서술.
- 외부 의존은 목(`useValue`/`jest.fn`), DB 정확성은 실 DB/Testcontainers.
- 한 테스트는 하나의 행동을 검증. 과도한 목킹으로 구현에 결합시키지 않는다.
- 프로젝트에 이미 테스트 스타일이 있으면 **그 관례를 따른다**.

## 실행

- 단위: `npm test` / 특정 파일 `npm test -- order.service`
- e2e: `npm run test:e2e`
- 커버리지: `npm run test:cov`

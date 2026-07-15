# 프로젝트 구조 · CLI · 전제

## 버전 전제

- **NestJS 10+**, **Node 18+**, **TypeScript 5+**.
- `tsconfig.json`에 데코레이터 메타데이터 활성:
  ```json
  {
    "compilerOptions": {
      "experimentalDecorators": true,
      "emitDecoratorMetadata": true,
      "strict": true,
      "strictNullChecks": true
    }
  }
  ```
- 프로젝트가 다른 버전/설정이면 **그것에 맞춘다** — 여기 값은 그린필드 기본값이다.

## 모듈 구조

NestJS는 **기능(모듈) 단위**가 기본이다. 각 기능은 자기 모듈로 캡슐화한다.

```
src/
├── main.ts                       # NestFactory 부트스트랩, 전역 파이프/필터 등록
├── app.module.ts                 # 루트 모듈 (기능 모듈 import)
├── prisma/
│   ├── prisma.module.ts          # 전역 모듈로 PrismaService 제공
│   └── prisma.service.ts
├── order/                        # 기능 단위
│   ├── order.module.ts
│   ├── order.controller.ts       # web
│   ├── order.service.ts          # service
│   └── dto/
│       ├── create-order.dto.ts
│       └── order-response.dto.ts
├── product/
│   └── ...
└── common/                        # 공통(예외 필터, 인터셉터, 데코레이터)
    └── filters/problem-detail.filter.ts
```

레이어별로 나눈 프로젝트라면 그 구조를 유지한다.

## Nest CLI

가능하면 CLI로 보일러플레이트를 생성해 관례를 맞춘다:
```bash
nest g module order
nest g controller order --no-spec     # 테스트 파일 생성은 test-writer가 관리
nest g service order --no-spec
```

## 기능 모듈 템플릿

```ts
// order/order.module.ts
import { Module } from '@nestjs/common';
import { OrderController } from './order.controller';
import { OrderService } from './order.service';

@Module({
  controllers: [OrderController],
  providers: [OrderService],
  exports: [OrderService],   // 다른 모듈이 쓰면 export
})
export class OrderModule {}
```

## main.ts — 전역 설정

```ts
import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';
import { AppModule } from './app.module';
import { ProblemDetailFilter } from './common/filters/problem-detail.filter';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  app.useGlobalFilters(new ProblemDetailFilter());
  await app.listen(process.env.PORT ?? 3000);
}
bootstrap();
```

## 체크리스트

- [ ] 새 기능이 자기 모듈(`*.module.ts`)로 캡슐화됐는가?
- [ ] 모듈이 루트/상위 모듈에 import됐는가?
- [ ] 전역 `ValidationPipe`·예외 필터가 `main.ts`에 등록됐는가?
- [ ] `tsconfig`의 데코레이터 메타데이터가 활성인가?

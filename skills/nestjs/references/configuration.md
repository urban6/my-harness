# 설정 — ConfigModule · 환경변수 · 시크릿

## ConfigModule

`@nestjs/config`로 환경변수를 로드하고 타입 안전하게 접근한다.

```ts
// app.module.ts
@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,               // 어디서나 ConfigService 주입
      envFilePath: ['.env.local', '.env'],
      validationSchema: envValidationSchema,   // 부팅 시 검증(아래)
    }),
    // ...기능 모듈
  ],
})
export class AppModule {}
```

## 환경변수 검증 (부팅 시 실패 빠르게)

Joi 등으로 필수 변수를 검증해 잘못된 설정으로 뜨는 것을 막는다.

```ts
import * as Joi from 'joi';

export const envValidationSchema = Joi.object({
  NODE_ENV: Joi.string().valid('development', 'test', 'production').default('development'),
  PORT: Joi.number().default(3000),
  DATABASE_URL: Joi.string().required(),        // 없으면 부팅 실패
});
```

## 타입 안전 설정 접근

```ts
@Injectable()
export class SomeService {
  constructor(private readonly config: ConfigService) {}

  get dbUrl(): string {
    return this.config.getOrThrow<string>('DATABASE_URL');  // 없으면 예외
  }
}
```

- 그룹 설정은 `registerAs`로 네임스페이스화하면 응집도가 좋아진다:
  ```ts
  export default registerAs('order', () => ({
    maxItemsPerOrder: parseInt(process.env.ORDER_MAX_ITEMS ?? '50', 10),
  }));
  ```

## 시크릿 외부화 (중요)

- 자격증명·API 키·토큰을 **소스/커밋에 하드코딩하지 않는다**(backend-impl 절차의 "비밀값 하드코딩 금지"와 정렬).
- `.env`는 로컬 전용 — **커밋하지 않는다**(`.gitignore`). 예시는 `.env.example`로 키 목록만.
- 주입 경로: 환경변수 → 외부 시크릿 매니저(Vault, AWS/GCP Secrets Manager 등) → CI/CD 시크릿.
- `DATABASE_URL` 등 연결 문자열에 비밀번호가 포함되므로 특히 주의.

## 프로파일(환경)

- `NODE_ENV`로 환경을 구분하고, 필요하면 `.env.{NODE_ENV}`를 `envFilePath`에 추가.
- 테스트는 별도 `.env.test` + 테스트 DB(`testing.md`).

## 체크리스트

- [ ] `ConfigModule.forRoot({ isGlobal: true })`로 설정을 로드하는가?
- [ ] 필수 환경변수를 `validationSchema`로 부팅 시 검증하는가?
- [ ] `.env`가 `.gitignore`에 있고, 비밀값이 커밋되지 않는가?
- [ ] 설정 접근이 `ConfigService`(getOrThrow)로 타입 안전한가?

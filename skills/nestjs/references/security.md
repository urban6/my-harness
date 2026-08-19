# 보안 기본 — 비밀번호 해싱 · 민감 데이터

이 문서는 CRUD·회원가입 같은 **일반 기능을 구현할 때 놓치기 쉬운 보안 기본**을 다룬다. 전체 인증/인가(Passport 전략, Guards, JWT, OAuth2)는 프로젝트가 그것을 도입한 경우 그 관례를 따르고, 여기서는 **데이터를 안전하게 다루는 최소 원칙**에 집중한다.

## 원칙

- **비밀번호·시크릿을 평문으로 저장하지 않는다.** 비밀번호는 단방향 해시로 저장한다.
- **민감 값을 응답에 노출하지 않는다.** 비밀번호 해시조차 응답 DTO에 넣지 않는다(원칙: 응답에는 필요한 필드만).
- **민감 값을 로그에 남기지 않는다.** 요청 바디나 Prisma 모델을 통째로 로깅하면 비밀번호·토큰이 샐 수 있다.
- **"나중에 해시" 주석으로 미루지 않는다.** 회원가입·비밀번호 변경 기능을 만들면 **그 자리에서** 해싱을 코드로 반영한다. 평문 저장 코드는 그 자체로 결함이다.

## 비밀번호 해싱 — 해셔를 프로바이더로

Node 생태계 기본 선택은 `bcrypt`, 더 강한 기본값을 원하면 `@node-rs/argon2`. 라이브러리를 서비스에서 직접 부르지 말고 **프로바이더로 감싸 주입**한다(교체 가능성 + 테스트 용이성).

```bash
npm i bcrypt && npm i -D @types/bcrypt
```

```ts
// auth/password-hasher.ts
import { Injectable } from '@nestjs/common';
import * as bcrypt from 'bcrypt';

@Injectable()
export class PasswordHasher {
  private readonly rounds = 10; // bcrypt — 솔트 내장

  hash(raw: string): Promise<string> {
    return bcrypt.hash(raw, this.rounds);
  }

  matches(raw: string, hash: string): Promise<boolean> {
    return bcrypt.compare(raw, hash);
  }
}
```

프로바이더로 등록해 어느 서비스에서든 주입한다.

```ts
@Module({
  providers: [MemberService, PasswordHasher],
  exports: [PasswordHasher],
})
export class MemberModule {}
```

### 저장 시 — 해시해서 저장

```ts
@Injectable()
export class MemberService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly hasher: PasswordHasher,
  ) {}

  async signUp(dto: SignUpDto): Promise<MemberResponseDto> {
    const exists = await this.prisma.member.findUnique({ where: { email: dto.email } });
    if (exists) {
      throw new ConflictException(`이미 사용 중인 이메일입니다: ${dto.email}`);
    }
    const passwordHash = await this.hasher.hash(dto.password); // 평문 아님
    const created = await this.prisma.member.create({
      data: { email: dto.email, passwordHash, nickname: dto.nickname },
    });
    return this.toResponse(created);
  }
}
```

### 검증 시 — matches로 비교

```ts
if (!(await this.hasher.matches(rawPassword, member.passwordHash))) {
  throw new UnauthorizedException();
}
```

- 저장된 해시를 **복호화하지 않는다**(단방향). 로그인 검증은 `matches(raw, hash)`로 한다.
- Prisma 모델 필드명을 `passwordHash`처럼 두면 "이건 해시"라는 의도가 드러난다.

## 응답·직렬화에서 민감 필드 제외

- 응답 DTO에 비밀번호/해시 필드를 **아예 넣지 않는 것**이 가장 안전하다(`web-layer.md`의 "노출할 필드만" 원칙).
- Prisma 모델을 직접 직렬화해야 하는 불가피한 경우에만 `class-transformer`의 `@Exclude()` + `ClassSerializerInterceptor`를 쓴다 — 하지만 기본은 DTO 분리.

```ts
export class MemberResponseDto {
  id: number;
  email: string;
  nickname: string;
  createdAt: Date;
  // password/passwordHash 필드 없음
}
```

Prisma 쿼리 단계에서 아예 안 가져오는 것도 좋은 방어다.

```ts
this.prisma.member.findMany({
  select: { id: true, email: true, nickname: true, createdAt: true }, // passwordHash 제외
});
```

## 인증/인가를 도입한 프로젝트라면

Guards·`@nestjs/passport`·JWT 전략이 이미 있으면 **그 관례를 따른다**. 새로 설계하지 말고 기존 가드 적용 범위·토큰 만료·리프레시 정책에 맞춘다. 토큰 시크릿은 코드에 두지 않고 `ConfigModule`+환경변수로 주입한다(`configuration.md`).

## 체크리스트

- [ ] 비밀번호가 해셔로 해시되어 저장되는가(평문 저장 없음)?
- [ ] 로그인/검증이 `matches()`(bcrypt `compare`)로 이뤄지는가(복호화 시도 없음)?
- [ ] 비밀번호·해시·토큰이 응답 DTO/바디에 노출되지 않는가?
- [ ] 요청 바디·Prisma 모델을 통째로 로깅해 민감 값이 새지 않는가?
- [ ] 자격증명·키가 코드/커밋에 하드코딩되지 않았는가(`configuration.md`)?

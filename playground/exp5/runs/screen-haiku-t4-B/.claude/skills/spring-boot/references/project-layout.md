# 프로젝트 구조 · 빌드 · 버전 전제

## 버전 전제

- **Spring Boot 3.x** → Jakarta EE 9+ 네임스페이스. import는 `jakarta.persistence.*`, `jakarta.validation.*` (구버전 `javax.*` 아님).
- **Java 21**(LTS) 기준. record·switch 패턴 매칭·`var` 활용. 프로젝트가 17이면 record까지는 동일하게 쓴다.
- 프로젝트가 이미 다른 버전이면 **그 버전에 맞춘다** — 여기 값은 그린필드 기본값이다.

## 패키지 구조

두 방식이 있고, **기존 프로젝트의 방식을 따른다**. 관례가 없을 때 기본은 **기능별(by-feature)** 이다 — 응집도가 높고 기능 단위로 탐색하기 쉽다.

```
com.example.app
├── AppApplication.java              # @SpringBootApplication
├── order/                           # 기능(도메인) 단위
│   ├── OrderController.java         # web
│   ├── OrderService.java           # service
│   ├── OrderRepository.java        # persistence
│   ├── Order.java                  # entity
│   └── dto/
│       ├── CreateOrderRequest.java
│       └── OrderResponse.java
├── product/
│   └── ...
└── common/                          # 공통(예외 핸들러, 설정, 유틸)
    ├── error/GlobalExceptionHandler.java
    └── config/
```

레이어별(by-layer: `controller/`, `service/`, `repository/`, `domain/`)로 나눈 프로젝트라면 그 구조를 유지한다.

## 빌드 설정

프로젝트에 있는 도구를 쓴다. 새로 시작할 때 기본은 **Gradle(Kotlin DSL)**.

### Gradle — `build.gradle.kts` (핵심 발췌)
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.+"
    id("io.spring.dependency-management") version "1.1.+"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> { useJUnitPlatform() }
```

### Maven — `pom.xml` (핵심 발췌)
```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.4.0</version>
</parent>
<properties>
  <java.version>21</java.version>
</properties>
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
</dependencies>
```

## 체크리스트

- [ ] 새 파일이 프로젝트의 기존 패키지 구조(by-feature/by-layer)를 따르는가?
- [ ] import가 `jakarta.*`인가(Spring Boot 3.x)?
- [ ] 새 의존성이 정말 필요한가? 스타터로 충분한 것을 개별 추가하지 않았는가?
- [ ] 진입점 `@SpringBootApplication`의 베이스 패키지 아래에 컴포넌트가 있는가(컴포넌트 스캔 대상)?

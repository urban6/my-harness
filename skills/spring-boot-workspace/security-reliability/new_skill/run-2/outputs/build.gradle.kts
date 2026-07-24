plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 전체 시큐리티 스타터 대신 crypto 모듈만 — PasswordEncoder(BCrypt)를 위해.
    // (필터체인/기본 인증을 켜지 않아 이 엔드포인트가 막히지 않는다.)
    implementation("org.springframework.security:spring-security-crypto")

    // 그린필드 실행/테스트용 임베디드 DB. 실제 운영 DB로 교체 가능.
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> { useJUnitPlatform() }

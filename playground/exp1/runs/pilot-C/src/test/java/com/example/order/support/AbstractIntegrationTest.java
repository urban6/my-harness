package com.example.order.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 통합 테스트 공통 베이스. Flyway + ddl-auto=validate 를 그대로 사용한다(운영 설정과 동일).
 * 새 통합 테스트는 이 클래스를 상속한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mvc;
}

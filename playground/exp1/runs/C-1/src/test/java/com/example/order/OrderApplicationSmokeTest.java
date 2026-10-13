package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.order.support.AbstractIntegrationTest;

/** 기동 스모크: 컨텍스트 로드 + Flyway V1 적용 + Hibernate validate 통과. */
class OrderApplicationSmokeTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoads_andFlywayV1Applied() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tables).contains("products", "orders", "order_items");
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class))
                .isEqualTo(1);
    }
}

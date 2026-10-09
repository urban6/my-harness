package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractApiTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlywaySmokeTest extends AbstractApiTest {

    @Test
    void contextStarts_andFlywayAppliesV1() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("products", "orders", "order_items");
    }
}

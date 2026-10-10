package com.example.order.common.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** JSON 역직렬화 엄격성 (01 설계 1.4): 타입이 어긋난 스칼라는 모두 400이 되도록 강제변환을 막는다. */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            // 문자열 필드에 숫자/불리언 금지
            mapper.coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            // 숫자/불리언 필드에 문자열(빈 문자열 포함) 금지
            for (LogicalType type : new LogicalType[] {LogicalType.Integer, LogicalType.Float, LogicalType.Boolean}) {
                mapper.coercionConfigFor(type)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
            }
            // 정수 필드에 소수 금지
            mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        });
    }
}

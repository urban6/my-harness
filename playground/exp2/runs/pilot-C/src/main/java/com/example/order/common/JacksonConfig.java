package com.example.order.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot 자동 구성 ObjectMapper 를 대체하지 않고 customize 한다 (C2, 설계 01 6.3 "타입 불일치" -> 400).
 * - 시각 필드에 JSON 숫자 -> 실패 (epoch 로 받아들이지 않음)
 * - 문자열 필드에 JSON 숫자/불리언 -> 실패 (문자열로 강제 변환하지 않음)
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictScalarCoercionCustomizer() {
        return builder -> builder.deserializerByType(OffsetDateTime.class, new StrictOffsetDateTimeDeserializer())
                .postConfigurer(mapper -> {
            mapper.coercionConfigFor(LogicalType.DateTime)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
    }

    /**
     * jsr310 의 OffsetDateTime 역직렬화는 숫자 토큰을 epoch 로 받아들이고 CoercionConfig(DateTime)를 보지 않는다.
     * 문자열 토큰만 허용하고 실제 파싱은 기존 jsr310 역직렬화기에 위임한다 (오프셋 없는 문자열은 기존대로 실패).
     */
    static class StrictOffsetDateTimeDeserializer extends StdDeserializer<OffsetDateTime> {
        StrictOffsetDateTimeDeserializer() {
            super(OffsetDateTime.class);
        }

        @Override
        public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_STRING) {
                return (OffsetDateTime) ctxt.handleUnexpectedToken(OffsetDateTime.class, p);
            }
            return InstantDeserializer.OFFSET_DATE_TIME.deserialize(p, ctxt);
        }
    }
}

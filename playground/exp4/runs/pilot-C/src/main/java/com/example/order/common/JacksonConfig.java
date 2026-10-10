package com.example.order.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 01 §0.3: JSON 숫자/불리언 토큰을 String 필드로 암묵 변환하지 않는다(→ HttpMessageNotReadable → 400).
 * {@code allow-coercion-of-scalars: false} 는 String 대상에는 효과가 없으므로 엄격한 String 역직렬화기를 둔다.
 * 문자열 토큰은 기존과 동일하게 그대로(trim 없이) 읽는다.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictStringCustomizer() {
        SimpleModule module = new SimpleModule("strict-string");
        module.addDeserializer(String.class, new StrictStringDeserializer());
        return builder -> builder.modulesToInstall(module);
    }

    static final class StrictStringDeserializer extends StdScalarDeserializer<String> {

        StrictStringDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) {
                return p.getText();
            }
            if (p.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NULL)) {
                return getNullValue(ctxt);
            }
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }
    }
}

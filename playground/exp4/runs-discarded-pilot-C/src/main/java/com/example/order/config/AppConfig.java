package com.example.order.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({OrderProperties.class, PaymentGatewayProperties.class})
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * String 대상 역직렬화는 JSON 문자열 토큰만 허용한다 (name: 123, cardToken: 1 -> 400).
     * Jackson 기본 StringDeserializer 는 숫자/불리언을 문자열로 강제 변환한다.
     */
    @Bean
    public Module strictStringModule() {
        SimpleModule module = new SimpleModule("strict-string");
        module.addDeserializer(String.class, new StrictStringDeserializer());
        return module;
    }

    static final class StrictStringDeserializer extends StdScalarDeserializer<String> {
        StrictStringDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.hasToken(JsonToken.VALUE_STRING)) {
                return p.getText();
            }
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }
    }
}

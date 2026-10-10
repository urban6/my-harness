package com.example.order.common.config;

import com.example.order.payment.PaymentGatewayProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentGatewayConfig {

    @Bean
    public RestClient pgRestClient(PaymentGatewayProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.timeout())
                .version(HttpClient.Version.HTTP_1_1) // JDK HttpServer 스텁과의 h2c 업그레이드 이슈 회피
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
        rf.setReadTimeout(props.timeout());
        return RestClient.builder()
                .baseUrl(stripTrailingSlash(props.url()))
                .requestFactory(rf)
                .build();
    }

    private static String stripTrailingSlash(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}

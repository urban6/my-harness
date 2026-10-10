package com.example.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@DisplayName("R5.6·R7.3 PG 연결 실패")
class PaymentGatewayClientTest {

    @Test
    @DisplayName("연결할 수 없으면 결제·환불 모두 PAYMENT_GATEWAY_UNAVAILABLE")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PaymentGatewayClient client = new PaymentGatewayClient(RestClient.builder(),
                new PaymentGatewayProperties("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2)));

        assertThatThrownBy(() -> client.pay("key", 1L, 1000L, "tok"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
        assertThatThrownBy(() -> client.refund("pay_1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
    }
}

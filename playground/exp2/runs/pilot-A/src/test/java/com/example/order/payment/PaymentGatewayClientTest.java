package com.example.order.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R5.6/R7.3 PG 연결 실패")
class PaymentGatewayClientTest {

    @Test
    @DisplayName("연결할 수 없으면 PG 장애로 취급한다")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PaymentGatewayClient client = new PaymentGatewayClient(new ObjectMapper(),
                "http://127.0.0.1:" + closedPort, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.pay("key", 1, 1000, "tok"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThatThrownBy(() -> client.refund("pay-1"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
    }
}

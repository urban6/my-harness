package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import org.springframework.stereotype.Service;

/** 비트랜잭션 파사드: 업무 트랜잭션 커밋 이후에 거절(402)을 던져 PAYMENT_FAILED/복원이 유지되게 한다. */
@Service
public class PaymentFacade {
    private final PaymentTxService txService;

    public PaymentFacade(PaymentTxService txService) {
        this.txService = txService;
    }

    public OrderResponse pay(long id, String idempotencyKey, String cardToken) {
        PaymentTxService.PayOutcome outcome = txService.payInTx(id, idempotencyKey, cardToken);
        if (outcome.declined()) {
            throw new ApiException(ErrorCode.PAYMENT_DECLINED, "payment was declined");
        }
        return outcome.order();
    }
}

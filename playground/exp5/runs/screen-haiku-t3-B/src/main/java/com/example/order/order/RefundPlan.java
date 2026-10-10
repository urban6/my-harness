package com.example.order.order;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Money moved by one refund: a partial refund request, or the refund a cancellation makes of every remaining unit.
 *
 * <p>P4.5: after a refund the refunded total is floor(totalPrice x refundedGross / subtotal), where refundedGross is the
 * sum of unitPrice x refundedQuantity; this refund pays the difference, so refunding everything pays exactly totalPrice.
 * P4.6: the card is refunded first and whatever it cannot take goes back to the points balance.
 */
record RefundPlan(Map<Long, Long> quantities, long amount, long cardAmount, long pointAmount, boolean fullyRefunded) {

    /**
     * @param requested productId to units refunded by this plan; the caller has checked each against the order's
     *                  remaining units
     */
    static RefundPlan of(Order order, Map<Long, Long> requested) {
        Map<Long, Long> before = new HashMap<>();
        Map<Long, Long> after = new HashMap<>();
        boolean full = true;
        for (Order.Item item : order.items()) {
            long units = requested.getOrDefault(item.productId(), 0L);
            before.put(item.productId(), item.refundedQuantity());
            after.put(item.productId(), item.refundedQuantity() + units);
            full &= item.refundedQuantity() + units == item.quantity();
        }
        long cumulativeBefore = cumulative(order, before);
        long amount = cumulative(order, after) - cumulativeBefore;
        // the card has already paid back min(refunded so far, cardAmount) because it is always refunded first
        long cardRefundedSoFar = Math.min(cumulativeBefore, order.cardAmount());
        long card = Math.min(amount, order.cardAmount() - cardRefundedSoFar);
        return new RefundPlan(requested, amount, card, amount - card, full);
    }

    private static long cumulative(Order order, Map<Long, Long> refundedQuantities) {
        long gross = 0;
        for (Order.Item item : order.items()) {
            gross = Math.addExact(gross,
                    Math.multiplyExact(item.unitPrice(), refundedQuantities.get(item.productId())));
        }
        return BigInteger.valueOf(order.totalPrice()).multiply(BigInteger.valueOf(gross))
                .divide(BigInteger.valueOf(order.subtotal())).longValueExact();
    }
}

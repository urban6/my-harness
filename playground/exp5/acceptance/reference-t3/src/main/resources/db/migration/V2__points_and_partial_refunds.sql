-- P1: point accounts. A missing row means balance 0.
CREATE TABLE point_accounts (
    user_id TEXT   PRIMARY KEY,
    balance BIGINT NOT NULL,
    CONSTRAINT point_accounts_balance_nonneg CHECK (balance >= 0)
);

-- P2.5 / P4: points used by the order, refunded totals (overall and card part).
ALTER TABLE orders
    ADD COLUMN point_amount         BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN refunded_amount      BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN card_refunded_amount BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT orders_point_amount_range CHECK (point_amount >= 0 AND point_amount <= total_price),
    ADD CONSTRAINT orders_refunded_range CHECK (refunded_amount >= 0 AND refunded_amount <= total_price),
    ADD CONSTRAINT orders_card_refunded_range
        CHECK (card_refunded_amount >= 0 AND card_refunded_amount <= total_price - point_amount);

ALTER TABLE order_items
    ADD COLUMN refunded_quantity BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT order_items_refunded_range CHECK (refunded_quantity >= 0 AND refunded_quantity <= quantity);

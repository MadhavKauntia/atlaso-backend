-- Server-side checkout binding (trip/user/quantity/expected amount) for a Razorpay order,
-- so payment verification can't be tricked by client-supplied values.
CREATE TABLE checkouts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    razorpay_order_id VARCHAR(64) NOT NULL UNIQUE,
    trip_id           UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL,
    quantity          INTEGER NOT NULL,
    amount_minor      BIGINT NOT NULL,
    currency          VARCHAR(8) NOT NULL DEFAULT 'INR',
    coupon_code       VARCHAR(64),
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_checkouts_user ON checkouts(user_id);

-- One order per Razorpay payment / order id — makes payment recording idempotent at the DB
-- level (not just app-level) so a payment can't be replayed into multiple orders.
CREATE UNIQUE INDEX ux_orders_razorpay_payment_id ON orders(razorpay_payment_id) WHERE razorpay_payment_id IS NOT NULL;
CREATE UNIQUE INDEX ux_orders_razorpay_order_id ON orders(razorpay_order_id) WHERE razorpay_order_id IS NOT NULL;

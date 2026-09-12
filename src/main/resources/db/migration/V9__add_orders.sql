CREATE SEQUENCE IF NOT EXISTS order_number_seq START 10001;

CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number              BIGINT NOT NULL,
    trip_id             UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    book_title          VARCHAR(255),
    razorpay_order_id   VARCHAR(64),
    razorpay_payment_id VARCHAR(64),
    payment_method      VARCHAR(32),
    amount_minor        BIGINT NOT NULL,
    currency            VARCHAR(8) NOT NULL DEFAULT 'INR',
    quantity            INTEGER NOT NULL DEFAULT 1,
    customer_name       VARCHAR(255),
    customer_email      VARCHAR(255),
    status              VARCHAR(32) NOT NULL DEFAULT 'PAID',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_trip ON orders(trip_id);
CREATE INDEX idx_orders_payment ON orders(razorpay_payment_id);

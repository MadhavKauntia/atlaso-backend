CREATE TABLE coupons (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(64)  NOT NULL,
    razorpay_offer_id   VARCHAR(64)  NOT NULL,
    description         VARCHAR(255),
    -- Preview-only fields; Razorpay is the source of truth for the real discount.
    discount_type       VARCHAR(16),          -- PERCENT | FLAT
    discount_value      BIGINT,               -- percent (e.g. 20) or flat paise
    max_discount_minor  BIGINT,               -- cap for percent discounts, nullable
    min_amount_minor    BIGINT,               -- mirrors the offer's min_amount, nullable
    force_offer         BOOLEAN NOT NULL DEFAULT FALSE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from          TIMESTAMP,
    valid_until         TIMESTAMP,
    max_uses            INTEGER,              -- null = unlimited
    used_count          INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_coupons_code ON coupons (UPPER(code));

-- Record which coupon/offer was applied to an order. amount_minor already holds the
-- discounted captured amount; discount_minor is the savings (list - captured).
ALTER TABLE orders ADD COLUMN coupon_code       VARCHAR(64);
ALTER TABLE orders ADD COLUMN razorpay_offer_id VARCHAR(64);
ALTER TABLE orders ADD COLUMN discount_minor    BIGINT;

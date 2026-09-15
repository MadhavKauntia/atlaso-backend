-- Shipping details captured on the checkout at create-order time (previously only sent at
-- /verify). Persisting them here lets the Razorpay webhook record a complete, shippable order
-- even if the buyer's browser dies before the client-side /verify call lands.
-- Lengths mirror the orders table (V10) exactly, so a value that fits on the checkout always
-- fits on the order it's copied into — no insert can fail on a length mismatch at record time.
ALTER TABLE checkouts
    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255),
    ADD COLUMN city          VARCHAR(128),
    ADD COLUMN state         VARCHAR(128),
    ADD COLUMN pincode       VARCHAR(32),
    ADD COLUMN ship_country  VARCHAR(64),
    ADD COLUMN phone         VARCHAR(32);

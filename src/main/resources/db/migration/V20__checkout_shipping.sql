-- Shipping details captured on the checkout at create-order time (previously only sent at
-- /verify). Persisting them here lets the Razorpay webhook record a complete, shippable order
-- even if the buyer's browser dies before the client-side /verify call lands.
ALTER TABLE checkouts
    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255),
    ADD COLUMN city          VARCHAR(120),
    ADD COLUMN state         VARCHAR(120),
    ADD COLUMN pincode        VARCHAR(16),
    ADD COLUMN ship_country  VARCHAR(80),
    ADD COLUMN phone         VARCHAR(32);

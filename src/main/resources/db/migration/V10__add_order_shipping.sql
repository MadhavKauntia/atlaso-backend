ALTER TABLE orders
    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255),
    ADD COLUMN city          VARCHAR(128),
    ADD COLUMN state         VARCHAR(128),
    ADD COLUMN pincode       VARCHAR(32),
    ADD COLUMN ship_country  VARCHAR(64),
    ADD COLUMN phone         VARCHAR(32);

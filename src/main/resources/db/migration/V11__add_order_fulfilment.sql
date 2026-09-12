ALTER TABLE orders
    ADD COLUMN book_id         UUID REFERENCES books(id) ON DELETE SET NULL,
    ADD COLUMN tracking_number VARCHAR(128),
    ADD COLUMN shipped_at      TIMESTAMP;

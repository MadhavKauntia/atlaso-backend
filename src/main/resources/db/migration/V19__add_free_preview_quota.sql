-- Free book-preview quota. The first generation of a trip runs its (paid) photo analysis and
-- reserves one preview; a paid order resets the allowance. Bounds pre-payment analysis spend
-- per identity.
ALTER TABLE users ADD COLUMN free_previews_remaining INT NOT NULL DEFAULT 3;

-- Durable per-trip reservation: set when a preview is consumed for this trip's analysis, and held
-- across retries so a failed-then-retried generation can't perform paid analysis without a charge.
ALTER TABLE trips ADD COLUMN preview_charged_at TIMESTAMP;

-- One book per (trip, version): a database guard against duplicate versions from concurrent
-- generations (which are also serialized by a pessimistic lock on the trip row).
ALTER TABLE books ADD CONSTRAINT uq_books_trip_version UNIQUE (trip_id, version);

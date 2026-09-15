-- Free book-preview quota per user. The first generation of a NEW trip runs that trip's (paid)
-- photo analysis and consumes one preview; a paid order resets it to the full quota. Bounds
-- pre-payment OpenAI analysis spend per identity.
ALTER TABLE users ADD COLUMN free_previews_remaining INT NOT NULL DEFAULT 3;

-- One book per (trip, version). Serializes concurrent first-generation submits so a double-submit
-- can't create two version-1 books or charge the quota twice — the losing INSERT hits this
-- constraint and is handled as a duplicate submit.
ALTER TABLE books ADD CONSTRAINT uq_books_trip_version UNIQUE (trip_id, version);

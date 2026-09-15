-- Persist the stored thumbnail size so confirmed thumbnails are counted in the per-trip byte
-- quota (a consumed grant drops out of the reserved-bytes sum).
ALTER TABLE photos ADD COLUMN thumbnail_size_bytes BIGINT;

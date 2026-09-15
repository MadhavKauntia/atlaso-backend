-- Track the reserved thumbnail size so confirm can verify the thumbnail's actual size too,
-- and so it counts toward the trip's cumulative byte quota.
ALTER TABLE upload_grants ADD COLUMN thumbnail_max_size_bytes BIGINT;

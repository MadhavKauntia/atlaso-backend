-- Server-recorded upload initiations, so confirm can only register keys we actually handed
-- out (bound to the trip + photoId), exactly once.
CREATE TABLE upload_grants (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id        UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    photo_id       UUID NOT NULL,
    storage_key    VARCHAR(512) NOT NULL,
    thumbnail_key  VARCHAR(512),
    content_type   VARCHAR(64) NOT NULL,
    max_size_bytes BIGINT NOT NULL,
    consumed       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_upload_grants_trip_photo ON upload_grants(trip_id, photo_id);

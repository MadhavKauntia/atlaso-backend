-- Guest capability token (SHA-256 hex) for guest trips, so a trip UUID alone can't be used
-- to upload to or claim the trip. Nullable: existing trips are grandfathered.
ALTER TABLE trips ADD COLUMN guest_token_hash VARCHAR(64);

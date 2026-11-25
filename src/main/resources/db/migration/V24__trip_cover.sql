-- Cover choices made on the cover step (step 1), saved on the trip as they're picked so an
-- in-progress trip shows its name and cover in the account page and can be resumed on any
-- device. Copied onto the book when it's generated. The title lives in trips.name.
ALTER TABLE trips
    ADD COLUMN cover_country VARCHAR(64),
    ADD COLUMN cover_palette_id VARCHAR(64),
    ADD COLUMN cover_subtitle VARCHAR(255);

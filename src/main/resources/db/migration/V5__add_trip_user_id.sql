-- Clean up existing unowned data (dev environment only)
DELETE FROM pages;
DELETE FROM books;
DELETE FROM photos;
DELETE FROM trips;

ALTER TABLE trips ADD COLUMN user_id UUID NOT NULL REFERENCES users(id);
CREATE INDEX idx_trips_user_id ON trips(user_id);

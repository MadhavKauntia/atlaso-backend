-- Records when the "your book is ready" email was sent for a book, so the async
-- generation worker never double-sends (and only the initial version emails).
ALTER TABLE books ADD COLUMN ready_email_sent_at TIMESTAMP;

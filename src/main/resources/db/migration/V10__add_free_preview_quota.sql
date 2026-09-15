-- Free book-preview quota per user. Each first generation of a NEW trip consumes one; a paid
-- order resets it back to the full quota. Bounds pre-payment OpenAI analysis spend per identity.
ALTER TABLE users ADD COLUMN free_previews_remaining INT NOT NULL DEFAULT 3;

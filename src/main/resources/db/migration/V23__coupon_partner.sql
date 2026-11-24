-- Partner (B2B) coupons: the back cover reads "atlaso x <partner_name>" instead of "atlaso".
-- Independent of full_discount. partner_name is stored lowercase, max 16 chars.
ALTER TABLE coupons ADD COLUMN partner BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE coupons ADD COLUMN partner_name VARCHAR(16);

-- Snapshot of the partner on the order, so fulfilment/Slack can spot B2B orders.
ALTER TABLE orders ADD COLUMN partner_name VARCHAR(16);

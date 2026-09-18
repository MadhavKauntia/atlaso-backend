-- Full-discount coupons (100% off) skip the Razorpay flow entirely: create-order records the order
-- directly with no payment. Such coupons have no Razorpay offer, so the offer id becomes optional.
ALTER TABLE coupons ADD COLUMN full_discount BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE coupons ALTER COLUMN razorpay_offer_id DROP NOT NULL;

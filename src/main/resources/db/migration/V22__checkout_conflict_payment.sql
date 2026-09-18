-- When a captured Razorpay payment conflicts with an already-finalized order for the same trip, the
-- checkout is marked status='CONFLICT' and the captured payment id is stored here, so orphaned
-- charges are durably queryable for refund/reconciliation instead of only living in a log line.
ALTER TABLE checkouts ADD COLUMN razorpay_payment_id VARCHAR(64);

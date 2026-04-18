-- Allow trips to exist before a user claims them (guest upload flow)
ALTER TABLE trips ALTER COLUMN user_id DROP NOT NULL;

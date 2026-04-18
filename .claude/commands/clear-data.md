Clear all data from the Atlaso database.

Run this command:
1. `/opt/homebrew/opt/libpq/bin/psql -U postgres -d atlaso -c "TRUNCATE photos, pages, books, trips RESTART IDENTITY CASCADE;"`

Then confirm it succeeded. Note: S3 files must be cleared manually via the AWS console if needed.

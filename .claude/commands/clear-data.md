Clear all data from the Atlaso database.

Run this command:
1. `PGPASSWORD=BKjTaBgVhPKkpaMdXlLEMMfScrexcQvQ psql -h roundhouse.proxy.rlwy.net -U postgres -p 55827 -d railway -c "TRUNCATE photos, pages, books, trips RESTART IDENTITY CASCADE;"`

Then confirm it succeeded. Note: S3 files must be cleared manually via the AWS console if needed.

Clear all data from the Atlaso (Railway) Postgres database.

Do NOT use a hardcoded `psql` connection string — the Railway TCP proxy host/port and
credentials rotate, so any baked-in `roundhouse.proxy.rlwy.net:...` command will fail with
"server closed the connection unexpectedly". Instead, pipe SQL into `railway connect Postgres`,
which opens an authenticated tunnel using the local Railway CLI session.

Run this from the `atlaso-backend` directory (the Railway project must be linked):

```bash
echo "TRUNCATE photos, pages, books, trips RESTART IDENTITY CASCADE;
SELECT
  (SELECT COUNT(*) FROM photos) AS photos,
  (SELECT COUNT(*) FROM pages) AS pages,
  (SELECT COUNT(*) FROM books) AS books,
  (SELECT COUNT(*) FROM trips) AS trips,
  (SELECT COUNT(*) FROM upload_grants) AS upload_grants,
  (SELECT COUNT(*) FROM orders) AS orders,
  (SELECT COUNT(*) FROM checkouts) AS checkouts;" | railway connect Postgres 2>&1 | grep -viE "SSH key|SSH tunnel|newer Railway|railway upgrade"
```

Notes:
- `TRUNCATE ... CASCADE` on `trips` also clears everything that references it — `upload_grants`,
  `orders`, and `checkouts` — so the verify SELECT should show all zeros.
- This deliberately does NOT touch `users`, `coupons`, or `flyway_schema_history`. If a wipe of
  those is ever intended, ask first — `coupons` is live config (not seeded by a migration) and
  `flyway_schema_history` must never be truncated.
- S3 objects (photos/PDFs) are not affected — clear them via the AWS console if a clean bucket is needed.

Then confirm the counts are all zero.

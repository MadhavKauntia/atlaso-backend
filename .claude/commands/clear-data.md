Clear all data from the Atlaso database and delete all uploaded files.

Run these two commands:
1. `/opt/homebrew/opt/libpq/bin/psql -U postgres -d atlaso -c "TRUNCATE photos, pages, books, trips RESTART IDENTITY CASCADE;"`
2. `rm -rf /Users/madhavkauntia/Desktop/atlaso-backend/uploads`

Then confirm both succeeded.

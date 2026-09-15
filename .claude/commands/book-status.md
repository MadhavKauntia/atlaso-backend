Report the generation status of an Atlaso book (or the latest book for a trip).

Target — a book id OR a trip id: $ARGUMENTS

Connect via `railway connect Postgres` (creds rotate — never hardcode psql). Run from the
`atlaso-backend` directory with the project linked. The query below resolves either an exact book
id or, failing that, the latest book for a trip id:

```bash
railway connect Postgres 2>&1 <<'SQL' | grep -viE "SSH key|SSH tunnel|newer Railway|railway upgrade"
WITH target AS (
  SELECT id AS book_id, trip_id, status, version FROM books WHERE id = '$ARGUMENTS'
  UNION ALL
  SELECT id, trip_id, status, version FROM books
   WHERE trip_id = '$ARGUMENTS' AND NOT EXISTS (SELECT 1 FROM books WHERE id = '$ARGUMENTS')
   ORDER BY version DESC LIMIT 1
)
SELECT t.book_id, t.status, t.version,
  (SELECT COUNT(*) FROM pages  p WHERE p.book_id = t.book_id) AS pages,
  (SELECT COUNT(*) FROM photos p WHERE p.trip_id = t.trip_id) AS total_photos,
  (SELECT COUNT(*) FROM photos p WHERE p.trip_id = t.trip_id AND p.signals IS NOT NULL) AS analyzed,
  (SELECT COUNT(*) FROM photos p WHERE p.trip_id = t.trip_id AND p.signals IS NULL)     AS remaining
FROM target t;
SQL
```

## Interpret for the user

- **`GENERATING` + remaining > 0** → still in vision analysis (the long pole). Analysis runs
  ~1 photo/sec across a 4-thread pool, so **ETA ≈ `remaining × 1.5s`**. Selection + layout after
  that take only seconds, so analysis ≈ overall progress. Percent done ≈ `analyzed / total_photos`.
- **`READY_FOR_PREVIEW`** (or `EXPORTING_PDF` / `PDF_READY`) → done; `pages` = 50 when complete.
- **`FAILED`** → generation failed. Pull the exception:
  `railway logs 2>&1 | grep -viE "SSH key|SSH tunnel" | grep -iE "book-gen|Exception|ERROR" | tail -20`
- **No row** → the id matched no book/trip (e.g. the data was cleared).

Note: if the frontend reported "failed" but the DB shows `GENERATING`/`READY_FOR_PREVIEW`, that's a
client-side timeout, not a backend failure — the backend keeps going and finishes regardless.

If the user wants to know *why a specific photo was picked*, pull that page's `slots` JSONB
(`pages.slots`) to find the slot's `photoId`, then that photo's `signals` (aesthetic, blur,
sceneType, detectedObjects) and reason from the `PhotoSelector` weighting.

Run a SQL query against the Atlaso production Postgres (Railway) and return the result.

Query (or a natural-language request to translate to SQL): $ARGUMENTS

## Rules

- Connect ONLY via `railway connect Postgres` — the Railway TCP proxy host/port and password
  rotate, so hardcoded psql connection strings fail. Run from the `atlaso-backend` directory with
  the project linked.
- **Read-only by default.** If the request is a plain `SELECT` (or just asks to read/inspect
  data), run it. If it would MODIFY anything (`INSERT`/`UPDATE`/`DELETE`/`TRUNCATE`/`ALTER`/`DROP`),
  STOP and confirm with the user first — never mutate prod without explicit confirmation. (For a
  full data wipe, use `/clear-data` instead.)
- Always strip the CLI's tunnel/upgrade noise from the output.
- If `$ARGUMENTS` is empty, ask what to query. If it's natural language (e.g. "how many trips are
  there"), translate to a `SELECT`, show the SQL, then run it.

## Run

Pipe the SQL in (use a quoted heredoc when the query contains quotes, to avoid shell-escaping issues):

```bash
railway connect Postgres 2>&1 <<'SQL' | grep -viE "SSH key|SSH tunnel|newer Railway|railway upgrade"
<the query here>
SQL
```

Handy tables/columns for reference: `trips`, `photos` (JSONB `signals`, `metadata`), `books`
(`status`, `version`), `pages` (JSONB `slots`), `orders`, `checkouts`, `coupons`, `users`,
`upload_grants`. Photo `takenAt` is stored in `metadata->>'takenAt'` as epoch **seconds**.

Deploy the Atlaso backend to Railway and verify it came up cleanly.

Railway deploys from the local working directory via `railway up` (NOT git push). Run everything
from the `atlaso-backend` directory with the Railway project linked. Deploy only when the user has
asked for it.

## Steps

1. (Optional preflight) If backend code changed, sanity-check it builds before shipping:
   ```bash
   ./gradlew bootJar -q 2>&1 | tail -5
   ```

2. Kick off the deploy (detached so it returns immediately):
   ```bash
   railway up --detach 2>&1 | tail -6
   ```

3. Wait for the build, then confirm the app is serving. The build takes ~1–2 min and there's a
   brief `502` window while the new container boots, so **run this poll in the background**:
   ```bash
   for i in $(seq 1 40); do
     line=$(railway status 2>/dev/null | grep -i "atlaso-backend:")
     if ! echo "$line" | grep -qi "Building"; then echo "Build done: $line"; break; fi
     sleep 15
   done
   for j in $(seq 1 8); do
     code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 https://api.myatlaso.com/actuator/health)
     echo "health -> HTTP $code"
     { [ "$code" = "200" ] || [ "$code" = "401" ]; } && break
     sleep 8
   done
   ```
   `200` or `401` = app is serving (the actuator endpoint is secured, so `401` is healthy). A
   persistent `502` means it failed to boot.

4. **Verify it's actually healthy** — the critical step. `ddl-auto: validate` means a bad or
   mismatched migration silently fails startup:
   ```bash
   railway logs 2>&1 | grep -viE "SSH key|SSH tunnel" | grep -iE "Started AtlasoBackend|Flyway|Migrat|ERROR|ValidationException|schema" | tail -10
   ```
   Confirm you see `Started AtlasoBackendApplicationKt`, and — if a migration was added — a
   `Successfully applied ... migration` / `now at version vN` line. If you see a Flyway/validate
   error, or no "Started" line, the deploy is broken: report it, do NOT declare success.

Report back: build status, health code, and whether startup + any migration succeeded.

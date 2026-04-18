Start the full Atlaso stack locally: Spring Boot backend + Next.js frontend.

## Steps

1. Start the backend in the background:
```bash
cd /Users/madhavkauntia/Desktop/atlaso-backend && AWS_ACCESS_KEY_ID=AKIAZTJQZHW6O74QGSHK AWS_SECRET_ACCESS_KEY=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 ./gradlew bootRun --args='--spring.profiles.active=local' > /tmp/atlaso-backend.log 2>&1 &
```

2. Wait for the backend to be ready (polls until port 8080 responds):
```bash
until curl -s http://localhost:8080/actuator/health > /dev/null 2>&1 || curl -s http://localhost:8080/api/trips > /dev/null 2>&1; do sleep 2; done
```

3. Start the frontend in the background:
```bash
cd /Users/madhavkauntia/Desktop/atlaso-frontend && npm run dev > /tmp/atlaso-frontend.log 2>&1 &
```

4. Confirm both are running and tell the user:
- Backend: http://localhost:8080
- Frontend: http://localhost:3000
- Logs: `tail -f /tmp/atlaso-backend.log` and `tail -f /tmp/atlaso-frontend.log`

**Prerequisites:** PostgreSQL must be running at `localhost:5432/atlaso` (user: `postgres`, pass: `postgres`). If the backend fails to start, check `/tmp/atlaso-backend.log` for database connection errors.

**Important:** Always run `/start` at the beginning of each session. Background processes are tied to the shell session and will die when Claude Code's shell resets between tool calls. If either service goes down mid-session, re-run `/start`.

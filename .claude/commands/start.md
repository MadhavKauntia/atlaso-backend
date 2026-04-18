Start the full Atlaso stack locally: Spring Boot backend + Next.js frontend.

## Steps

1. Start the backend in the background:
```bash
cd /Users/madhavkauntia/Desktop/atlaso-backend && ./gradlew bootRun > /tmp/atlaso-backend.log 2>&1 &
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

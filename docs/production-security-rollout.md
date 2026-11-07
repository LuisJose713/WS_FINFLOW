# Production security migration — BLOCKING

FinFlow is **not** cleared for public release. Never deploy this branch into a live Render instance until all credentials and environment variables below are configured.

## Findings
- A JWT HMAC key was hard-coded in the Git history.
- Production PostgreSQL credentials were committed in `application-prod.properties` and a tracked `.env`.
- These values must be considered exposed even after this PR deletes the files, because Git history retains them.
- Existing JWTs were signed with the compromised key. Rotating JWT_SECRET intentionally invalidates all prior sessions.

## Prepare Render (before merge/redeploy)
1. Generate a new high-entropy random JWT secret (at least 32 bytes). Set `JWT_SECRET` in Render service secret environment variables; do not paste it in GitHub.
2. Rotate the Neon PostgreSQL role password in Neon. Update Render's `SPRING_DATASOURCE_PASSWORD` accordingly. Set `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` to verified connection parameters.
3. Confirm database backup and ability to restore before rotating and deploying.
4. Confirm Render health checks, test login, user A vs user B data separation and transactional CRUD with isolated test accounts after rollout.
5. Audit git history and third-party logs for old values; remove compromised key material from active systems. Git history cleanup requires a coordinated history rewrite and collaboration plan, not just deleting a file.

## Local development
Use `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `JWT_SECRET` from environment variables, never from tracked files. The CI workflow uses a test-only signing key.

## Rollback caveat
Reverting these source changes without rotating credentials would reintroduce known-exposed secrets. A safe rollback must retain externalized secret configuration and avoid restoring compromised keys.

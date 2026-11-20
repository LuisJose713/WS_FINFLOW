# FinFlow – Secure incremental synchronization V1

- Patched production Java: Amazon Corretto 21.0.13.1, checked during Docker build.
- Maven builder: disables affected accelerated Base64 intrinsic until patched.
- Existing AES-256-GCM encrypted fields, secrets and JWT verification are untouched.
- DB creates additive owner-scoped revision and event tables. On account deletion they cascade.
- All events have only resource type, revision and timestamp, no private financial data.
- All write events are recorded within the same SQL transaction, serialized per account.
- `GET /sync/cursor` initializes an authenticated session's cursor.
- `GET /sync/changes?after=0&limit=100` lists owner-only refresh hints.
- Consumers reload existing GET endpoints after receiving hints; never use event data as finance state.
- Existing mobile and web API contracts remain compatible.
- Redis, SSE, virtual threads and full modular restructuring require load/compatibility tests;
  none is silently enabled as an unmeasured production dependency.

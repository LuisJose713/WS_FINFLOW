# FinFlow — zero-readable-financial-data rollout (NOT LIVE)

## Security contract
- The **client** (web / iOS / Android) creates a random 256-bit master key and keeps it outside PostgreSQL, GitHub, logs, analytics and backend configuration.
- Client AES-256-GCM uses a new unpredictable 96-bit nonce per record update.
- Authenticate (AAD) at minimum `finflow:private-vault:v1:<account-id>:<entry-uuid>` to prevent ciphertext swapping across accounts or records.
- Encrypt complete, versioned record JSON: amounts, account names, categories, dates, descriptions, goals, notes and financial relationships. Metadata `user_id, entry_id, nonce, revision, updated_at` remains visible.
- Use independent high-entropy recovery key handling / secure cross-device enrollment. Do not silently derive the encryption key from a JWT, login email, or server-stored secret.
- A lost last copy of the private recovery key means irrecoverable financial data. Design key backup / multi-device onboarding before migration.
- The backend only authenticates, authorizes and stores opaque ciphertext. It cannot compute financial reports. All financial views/analytics and recurring rules must be computed locally by the trusted client.
- Signed-in users must not submit raw financial fields to old endpoints when the new client mode is enabled.
- Client reads must verify the AES-GCM authentication tag. Reject any unexpected version, missing key, invalid record schema, or tampered payload. No plaintext fallback after cutover.
- Passwords remain salted one-way password hashes. Login identifiers and compliance records need their own dedicated privacy redesign, separate from E2EE financial records.

## Stages (do NOT skip)
1. Add `private_vault_entries` in isolated QA; verify encrypted records can be inserted and retrieved only by their JWT owner, with robust optimistic concurrency, size and authentication tests.
2. Build E2EE implementations in all three clients, interoperable test vectors, local stats / budgets / search / PDF / recurring scheduler, secure device-key storage, and end-to-end multi-device sync.
3. Address the **existing plaintext** in users, transactions, categories, financial_accounts, account_transfers, goals, goal_contributions, budgets, monthly_budgets, planned_expenses, recurring_rules, privacy/account tables, logs and snapshots. Review legal constraints on consent records. Use hashed/blind-index lookup keys for login addresses rather than plaintext, and a narrow server-side contact-delivery capability where strictly required.
4. Run an authenticated **user-by-user migration**: export each user's existing records over TLS to the user's trusted client, encrypt locally, upload opaque records, read/decrypt and compare every field locally, verify record counts and referential integrity, then mark each user migrated. Account for updates made while migrating (dual-write via CLIENT ONLY or write freeze, never server-side plaintext mirrors).
5. After successful verification and retention/legal review, disable legacy routes across all supported client versions, permanently remove legacy plaintext rows using an approved production migration, audit logs/exports/backups and expire old snapshots/PITR copies. Keep a documented restore path that cannot roll back privacy guarantees.
6. Privacy acceptance test: privileged SQL may show only required identifiers, encrypted bytea ciphertext/nonces and non-sensitive operational metadata. Query for any remaining personal plaintext; inspect database dump and telemetry. Verify no backend-held key can decrypt financial data.

## Preliminary backend contract (experimental)
`GET /private-vault/entries?after=<uuid>&limit=100`: cursor-paginated opaque entries; 1..250.
`PUT /private-vault/entries/<uuid>`: {nonce, ciphertext, expectedRevision} with base64url strings. Revision 0 inserts; positive revision updates conditionally; HTTP 409 if stale.
`DELETE /private-vault/entries/<uuid>?expectedRevision=N`: version-checked deletion.

**Important:** this initial storage endpoint alone does **not** encrypt existing data or change the currently deployed application. Do not merge/release until full client migration, read isolation tests, key recovery UX and legacy deletion are verified.

## Operational acceptance
- No key ever appears in Neon, backend env, GitHub, crash reports or telemetry.
- A privileged SQL-only reader cannot read any customer financial text or amounts.
- An admin cannot decrypt ciphertext without a user's recovery key.
- Existing logins and active sessions remain valid; users receive an E2EE recovery-key enrollment flow.
- Functional parity (web/iOS/Android) and offline use, conflict resolution, tests and rollback are demonstrated.


## Release safety switch (2026-11-10)

The experimental controller is guarded with Spring property `finflow.private-vault.enabled`.
Its default is **false**. In QA only, after applying the SQL migration, set
`FINFLOW_PRIVATEVAULT_ENABLED=true` (Spring Boot relaxed binding) to enable it.
Do not set that flag on production during the experimental stage.

The web, iOS/macOS and Android branches now include ciphertext-only sync clients.
These clients are intentionally NOT yet wired to transactions, goals, accounts,
budgets, planned expenses, recurring jobs or analytics. They do not encrypt
existing rows, do not modify the live app, and must remain draft PRs until the
user-controlled recovery-key enrollment and migration are validated end-to-end.

**Do not claim full database encryption because the E2EE API builds.**
The release is complete only when old plaintext financial rows, backups and
other residual copies are accounted for and the database read audit passes.

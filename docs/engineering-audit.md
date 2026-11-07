# FinFlow — Initial engineering audit

Date: 2026-11-07
Scope: Web (finflow), API (WS_FINFLOW), iOS (Finflow_Swift).

## Verified from repository files

- API `pom.xml`: Java 21, Spring Boot 3.3.2, Spring Security, JPA, PostgreSQL, WebFlux and Web dependencies.
- API `pom.xml`: JJWT artifacts appear twice, with conflicting 0.11.5 and 0.12.x versions. Resolve only after inspecting JWT call sites and running tests.
- Web `package.json`: Angular 18 and TypeScript ~5.4.0. Check supported upgrade path, dependency compatibility and production build.
- iOS `README.md`: documents Keychain, JWT, offline queue for transaction create/delete, and states Xcode build and end-to-end tests remain unverified.

## Priority verification checklist

1. Inspect authentication and authorization: JWT signing/verification, expiration, refresh/logout, ownership checks, CORS and error responses.
2. Verify no committed secrets or environment-specific credentials; rotate any exposed secrets.
3. Verify monetary amounts use exact decimal arithmetic and database transactions; test concurrent updates and idempotency.
4. Define a shared API contract and check Angular/Swift DTO compatibility, date/time and currency formats.
5. Add repeatable API unit/integration tests, web build/test checks, and iOS Xcode CI on macOS.
6. Validate offline queue conflict resolution, retries, duplicate submissions and data isolation across users.
7. Audit dependency vulnerabilities and plan supported framework upgrades with regression tests.

## Change policy

- No direct commits to default branches.
- Small reviewable pull requests, with test evidence and rollback notes.
- Do not claim production readiness without passing security, functional and deployment checks.

This document is an initial audit, **not** a completed security assessment. Findings are limited to files inspected so far.

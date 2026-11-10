# FinFlow — phased browser session security plan

Status: **design only; NOT enabled in production**  
Applies to: Angular on Vercel, Spring Boot on Railway, Neon PostgreSQL and the separate SwiftUI client.

## Why this is necessary

The current web application stores a signed 5-hour Bearer JWT in `localStorage`.
That avoids classic ambient-cookie CSRF, but an XSS payload (or compromised browser extension)
can read the JWT. CORS cannot prevent that, and HTTPS protects traffic in transit, not
the user endpoint or the browser's developer tools. Never claim that traffic is
impossible to inspect or that client-held secrets are safe on compromised machines.

**Do not switch Angular to cross-site cookies between vercel.app and railway.app.**
Third-party cookie blocking, SameSite=None and CSRF would produce fragile auth,
especially on iOS/Safari. A same-origin BFF/proxy design is preferable.

## Proposed deployment architecture

1. Host the Angular app on Vercel at the canonical HTTPS origin.
2. Introduce a same-origin route `/api/**` forwarding to the Railway API.
   Explicitly disable caching for personalized `/api/**` responses and validate
   headers and HTTPS termination. Treat proxy/CDN forwarding as a security boundary.
3. Introduce opt-in web session endpoints in Spring Boot, independent of existing
   mobile Bearer authentication:
   - `POST /api/auth/session/login`: server validates credentials; sets
     `__Host-finflow-session` with `Secure; HttpOnly; SameSite=Lax; Path=/`.
     Use a short session duration and a server-side session identifier or
     rotating opaque refresh token; never expose this token in JavaScript.
   - `GET /api/auth/session/me`: returns only the caller's minimal identity.
   - `POST /api/auth/session/logout`: revokes **this** session and clears cookie;
     do not increment the global JWT token version (that would sign out every device).
   - For unsafe requests, require a CSRF token bound to the server-side session
     (synchronizer-token pattern or signed double-submit), validate Origin/Referer
     in defense in depth and use strict content type validation. Do not accept
     a static token or an unsigned cookie-only value.
4. Phase in an authenticated `/auth/session/exchange` endpoint to exchange a
   currently valid Bearer token for a cookie, where supported, **without
   invalidating the existing JWT**. Users should not be forced to log in when a
   deployment occurs. Phase out legacy web tokens only after observed adoption.
5. Keep the iOS client on its own Bearer/Keychain contract. Do not silently
   change its login response or JWT lifetime.

## Verification before enabling

- Separate dev/staging database, synthetic test accounts only. Production Neon
  is not for brute-force or XSS test campaigns.
- Two browsers plus mobile Safari: login, refresh, tab concurrency, logout,
  expiry, clock skew, session rotation, 401 races, deployment during login.
- XSS attempts cannot read HttpOnly cookies; test CSP and trusted templates;
  recognize that XSS can still submit same-origin requests as the user.
- CSRF: reject missing/bad tokens, cross-origin POST, DELETE and PATCH; prevent
  preflight bypass and insecure fallbacks. Test CORS with allowed/untrusted origins.
- Audit access controls for transactions, goals, categories, budgets, admin,
  invoices, exports, and privacy APIs against another test account.
- Measure p95 and error rates before/after rollout; add kill switch and rollback.

## Deployment controls

- The current `SecurityConfig.csrf().disable()` is intentional for the
  Authorization-header-only API, but **must not remain a blanket exemption**
  once cookie-authenticated endpoints exist.
- Any cookie-authenticated endpoints should live in a separate SecurityFilterChain
  with explicit CSRF and cookie policies, leaving legacy Bearer clients unchanged.
- Roll out behind environment flags and monitor logouts and CORS/CSRF failures.
- No real credentials or raw auth tokens in code, GitHub Actions, or diagnostics.

## Outstanding decisions

- Same-origin proxy route/canonical domain, cookie path and routing contract.
- Server session store: Postgres table with hashed opaque session handles,
  expiry and rotation/reuse detection; idempotent revoke and cleanup.
- CSRF implementation chosen for the selected Spring Security version.
- Multi-device session UX and incident response.

This document does not constitute implementation of HttpOnly web sessions.

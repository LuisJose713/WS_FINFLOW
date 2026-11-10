# FinFlow — safe activation of per-IP authentication throttling

Status: implementation **reviewed in PR**, disabled by default; not enabled in Railway production.

## Why two layers matter

- The existing `AuthRateLimiter` counts failures per normalized account identifier.
  It helps stop distributed password guessing of a single account.
- The new `AuthIpRateLimitFilter` counts **all** login or registration requests
  for each source address, independently of the account. It helps detect one
  source spraying many different account names.

The two controls complement each other. A single IP+username combined bucket
would not protect against spraying many distinct usernames. The new counters
reuse the existing `auth_rate_limits` table in Neon and use parameterized
atomic PostgreSQL upserts. The IP-derived identifiers are hashed and expire
with the existing cleanup; do not mistake SHA-256 of an IP for anonymization.

## Conditions before enabling in production

1. Validate one authoritative ingress: Railway public HTTP edge only.
   Railway rewrites `X-Real-IP` at that edge; private networking and TCP
   proxies do not have the same guarantee. Do not run the same trust setting
   if untrusted services or alternate ingress paths can reach the app.
2. Use a **separate** staging environment and synthetic identities to
   exercise 429, Retry-After, no blocking of GET/OPTIONS and race behavior.
3. Check one browser behind shared NAT, mobile carrier, school network and
   corporate network so legitimate users are not unexpectedly blocked.
4. Observe p95 login, rate of HTTP 429, database load and support reports;
   decide whether thresholds should be higher for your user base.
5. Enable flags on the Railway service only after checks:
   `FINFLOW_AUTH_IP_RATE_ENABLED=true`
   `FINFLOW_TRUST_RAILWAY_REAL_IP=true`
   Both flags default to false.
6. First monitoring window: review 429 and response time; roll back rapidly
   by setting `FINFLOW_AUTH_IP_RATE_ENABLED=false` and redeploying.

## Current limits

- 60 login attempts / five-minute started window / source IP.
- 20 registration attempts / five-minute started window / source IP.
- On exceeding: HTTP 429 with Retry-After: 300 seconds.

A large botnet requires additional controls at the edge (rate limits/WAF or
bot challenge), and a simple fixed-start window is not equivalent to a fully
sliding window. Per-account lockout must be monitored for targeted denial
of service attempts. Never test this on actual users' accounts.

## No auth session migration here

There is no cookie authentication or CSRF behavior change in this PR.
Existing Bearer JWTs, Neon data, the Angular login and SwiftUI are unchanged.

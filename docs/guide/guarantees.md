# Guarantees

What MailKT promises, and what remains your responsibility.

## MailKT guarantees

| Area | Guarantee |
|---|---|
| Reads | Survive reconnects. Scans keep their frozen snapshot and UIDVALIDITY integrity. |
| Downloads | Bounded by an explicit byte limit and never silently truncated. |
| Identity | A location or part reference never resolves to a different message or part. |
| Watchers | At-least-once delivery with in-collection duplicate suppression and catch-up after reconnects. |
| Sending | Never retried. Outcomes are explicit: `ACCEPTED`, `FAILED` or `UNKNOWN`. |
| Isolation | Closing or failing one mailbox never affects another. |
| Lifecycle | `close()` is idempotent and `Closed` is published exactly once. |
| Privacy | No addresses, content or secrets in logs, exception messages or `toString()`. |
| API surface | No Jakarta Mail or Angus types in public packages, enforced by an architecture test. |

## Your responsibilities

| Area | You provide |
|---|---|
| Token storage | Durable, encrypted, atomic `TokenStore`. |
| Pending authorizations | An `AuthorizationSessionStore` with atomic one-time `consume`, shared across instances. |
| Checkpoints | Persist only after your processing is durable. |
| Idempotency | De-duplicate side effects by account and Message-ID. |
| Unknown sends | Reconcile by Message-ID in the Sent folder before resending. |
| HTTP routes | The begin endpoint and the OAuth callback route. |
| Logging backend | Any SLF4J 2 implementation. |

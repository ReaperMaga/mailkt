# Introduction

MailKT is a coroutine-first Kotlin/JVM library for reading, watching and sending email through a managed `Mailbox`, with hosted OAuth2 for **Gmail** and **Outlook (Microsoft 365)**.

It runs entirely inside your backend process. MailKT never opens a browser, never starts an HTTP listener and never persists anything on its own. Everything that has to outlive a process (tokens, pending authorizations, checkpoints) crosses small interfaces or comes back as plain values you store.

## Why MailKT

Talking to IMAP directly is full of sharp edges: connections drop silently, UIDs become meaningless when UIDVALIDITY changes, attachments are downloaded whole just to read a file name, and a lost connection during SMTP `DATA` leaves you guessing whether the mail went out. MailKT turns each of these into an explicit, typed part of the API.

| Concern | What MailKT does |
|---|---|
| Lifecycle | One `Mailbox` per account with a `StateFlow<MailboxState>`, automatic generation-safe reconnects and idempotent `close()`. |
| Reading | Envelope, then structure, then selected parts. Filtering happens before any content is fetched. |
| Integrity | Message and part references carry UIDVALIDITY. A changed folder yields a typed failure, never a different message. |
| Watching | IMAP IDLE with catch-up from a checkpoint, at-least-once delivery and in-session duplicate suppression. |
| Sending | Outcomes are `ACCEPTED`, `FAILED` or `UNKNOWN` and are never retried automatically. |
| Models | Immutable MailKT types only. No Jakarta Mail or Angus type appears in the public API. |
| Privacy | Addresses, subjects, bodies and tokens are redacted from `toString()`, exception messages and logs. |

## Modules

| Module | Purpose |
|---|---|
| `core` | `Mailbox`, models, capabilities, lifecycle, IMAP/SMTP transport, MIME. |
| `gmail` | Hosted Google authorization-code flow and `imap.gmail.com` / `smtp.gmail.com`. |
| `outlook` | Hosted Microsoft (MSAL4J) flow and `outlook.office365.com` IMAP/SMTP. |
| `examples` | Compile-tested sources of every snippet in this documentation. |

::: tip Snippets are compiled
The Kotlin examples in the guides are imported directly from the `examples` module of the repository, which is compiled and tested on every build, so they cannot drift from the API.
:::

## How the pieces fit

<ArchitectureDiagram />

A provider client (`Gmail` or `Outlook`) handles authorization and token refresh. `open` turns stored tokens into a `Mailbox`, which owns the connection, its recovery loop and four capabilities:

- [`folders`](./folders) lists folders and resolves special-use ones like Inbox and Sent.
- [`messages`](./reading) pages, streams, downloads and [watches](./watching) mail.
- [`conversations`](./conversations) groups messages into threads.
- [`outbox`](./sending) composes, replies and sends.

## Next steps

- [Install](./installation) the modules you need.
- Follow the [quick start](./quick-start) to open a Gmail mailbox locally.
- Read the [persistence boundary](./persistence) before going to production.

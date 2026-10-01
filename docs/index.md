---
layout: home

hero:
  name: MailKT
  text: Email for Kotlin, done carefully.
  tagline: Coroutine-first IMAP and SMTP with hosted OAuth2 for Gmail and Outlook. Read, watch and send mail from your backend with explicit lifecycles, typed failures and no hidden persistence.
  image:
    src: /logo.svg
    alt: MailKT
  actions:
    - theme: brand
      text: Get started
      link: /guide/introduction
    - theme: alt
      text: Quick start
      link: /guide/quick-start
    - theme: alt
      text: GitHub
      link: https://github.com/ReaperMaga/mailkt

features:
  - icon: ⏻
    title: One lifecycle handle
    details: Each account is a Mailbox with a StateFlow of its state, generation-safe automatic reconnects and an idempotent close().
    link: /guide/lifecycle
    linkText: Mailbox lifecycle
  - icon: ✉
    title: Envelope-first reading
    details: Filter on cheap metadata, inspect the MIME structure, then download only the parts you need. Rejected mail costs no content download.
    link: /guide/reading
    linkText: Reading mail
  - icon: ↻
    title: Live watching with catch-up
    details: IMAP IDLE watchers resume from a checkpoint after restarts and reconnects and deliver at-least-once.
    link: /guide/watching
    linkText: Watching
  - icon: ⇄
    title: Conversations by real relations
    details: Threads are built from Message-ID, In-Reply-To and References, merged across folders, never guessed from subjects.
    link: /guide/conversations
    linkText: Conversations
  - icon: ✓
    title: Explicit send outcomes
    details: ACCEPTED, FAILED or UNKNOWN. Submissions are never retried behind your back, not even across reconnects.
    link: /guide/sending
    linkText: Sending
  - icon: 🔑
    title: Hosted OAuth2, your storage
    details: Gmail and Microsoft 365 authorization-code flows with PKCE. Tokens and checkpoints cross small interfaces you implement.
    link: /providers/authorization
    linkText: Authorization
---

<div class="home-snippet">

## A taste

```kotlin
gmail.open(MailAddress("alice@gmail.com")).use { mailbox ->
    val inbox = checkNotNull(mailbox.folders.special(SpecialUse.INBOX)).path

    mailbox.messages.watchEnvelopes(inbox, MessageQuery(seen = false)).collect { watched ->
        val structure = mailbox.messages.structure(watched.envelope.location)
        structure.attachments.filter { it.isPdf }.forEach { part ->
            val pdf = mailbox.messages.download(part.ref, maxBytes = 10L * 1024 * 1024)
            store(pdf.content.toByteArray())
        }
        saveCheckpoint(watched.next) // only after your processing is durable
    }
}
```

</div>

<style>
.home-snippet { max-width: 1152px; margin: 48px auto 0; padding: 0 24px; }
.home-snippet h2 { font-family: var(--vp-font-family-mono); font-size: 13px; font-weight: 500; text-transform: uppercase; letter-spacing: .08em; color: var(--vp-c-brand-1); border: 0; margin: 0 0 12px; padding: 0; }
@media (min-width: 640px) { .home-snippet { padding: 0 48px; } }
@media (min-width: 960px) { .home-snippet { padding: 0 64px; } }
</style>

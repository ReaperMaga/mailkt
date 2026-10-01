# Logging

MailKT logs through the **SLF4J 2 API** only. Add the backend of your choice (Logback, Log4j 2, `slf4j-simple`, ...) and configure routing and levels there.

## Categories

| Logger | Covers |
|---|---|
| `dev.reapermaga.mailkt.lifecycle` | Open, state transitions, reconnects, close. |
| `dev.reapermaga.mailkt.auth` | Authorization flow and token refresh. |
| `dev.reapermaga.mailkt.imap` | IMAP connections and commands. |
| `dev.reapermaga.mailkt.watch` | IDLE watchers and catch-up. |
| `dev.reapermaga.mailkt.sync` | Conversation synchronization. |
| `dev.reapermaga.mailkt.mime` | MIME parsing and building. |
| `dev.reapermaga.mailkt.smtp` | Submission. |

Log lines use structured key/value fields and a stable, one-way **mailbox correlation ID**, so you can follow one account across lines without logging its address.

## What is never logged

Addresses, tokens, authorization codes, headers, subjects, recipients, bodies, attachment names and provider error text. Model `toString()` implementations are redacted for the same reason, so accidental string interpolation of a `MailAddress` or `MessageEnvelope` does not leak data either.

## Example: Logback

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} %kvp - %msg%n</pattern></encoder>
  </appender>

  <logger name="dev.reapermaga.mailkt" level="INFO"/>
  <logger name="dev.reapermaga.mailkt.lifecycle" level="DEBUG"/>

  <root level="WARN"><appender-ref ref="STDOUT"/></root>
</configuration>
```

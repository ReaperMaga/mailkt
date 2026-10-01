# Error handling

All failures are typed subclasses of the sealed `MailException`. Messages are sanitized: they never contain credentials, message content, addresses or provider-supplied text, so they are safe to log and to show to operators.

Cancellation is never classified as a transport failure. A `CancellationException` always propagates unchanged.

## Handling read failures

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Errors.kt#errors{kotlin}

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Errors.kt#folder-errors{kotlin}

## What to do

| Exception | Typical cause | Recommended reaction |
|---|---|---|
| `AuthenticationRequired` | Refresh token revoked or expired. | Run the hosted flow again, then `reconnect()`. |
| `AuthorizationFailed(reason)` | Callback validation failed. | Show an error and let the user start again. |
| `ConnectionFailed(recovery)` | Opening or transport failed. | Retry `open` later. |
| `NotConnected(state)` | Mailbox is reconnecting or failed. | Retry the operation later, or observe `state`. |
| `MailboxClosed` | Operation on a closed mailbox. | Programming error or shutdown race. |
| `FolderNotFound(folder)` | Folder missing or not selectable. | Re-resolve with `folders.list()` / `special()`. |
| `MessageUnavailable(location)` | Message expunged or moved. | Skip it. |
| `IntegrityViolation(kind)` | UIDVALIDITY or part changed. | Rescan the folder; drop cached locations. |
| `LimitExceeded(limitBytes)` | Download larger than the limit. | Skip or retry with a larger explicit limit. |
| `MalformedMessage` | Unparseable MIME or unbuildable draft. | Skip, or fix the draft. |
| `InvalidCheckpoint` | Checkpoint from another mailbox, folder or format. | Start without a checkpoint. |
| `OutboxUnavailable` | No SMTP for this mailbox. | Enable sending in the provider config. |
| `DuplicateMailbox(id)` | Second live mailbox with the same id in a registry. | Reuse the existing mailbox. |
| `Unexpected` | Anything unclassified. | Log and report. |

See [Exceptions](/reference/exceptions) for the full type reference.

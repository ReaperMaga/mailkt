# Exceptions

All failures extend the sealed class `MailException` in `dev.reapermaga.mailkt.model`. Messages never contain credentials, message content, addresses or provider-supplied text.

| Type | Properties | Thrown when |
|---|---|---|
| `AuthenticationRequired` | | Credentials rejected or silent refresh impossible. |
| `AuthorizationFailed` | `reason: AuthorizationFailure` | Hosted-flow validation failed. |
| `ConnectionFailed` | `recovery: RecoveryReason` | Connecting or transport failed. |
| `MailboxClosed` | | Operation on a closed or closing mailbox. |
| `NotConnected` | `state: MailboxState` | Mailbox is reconnecting or failed. |
| `FolderNotFound` | `folder: FolderPath` | Folder missing or not selectable. |
| `MessageUnavailable` | `location: MessageLocation` | Message or part expunged or gone. |
| `IntegrityViolation` | `kind: IntegrityKind` | UIDVALIDITY or a part changed between selection and download. |
| `LimitExceeded` | `limitBytes: Long` | A byte limit was exceeded. |
| `MalformedMessage` | | A message could not be parsed or built. |
| `InvalidCheckpoint` | | Unsupported version, or a checkpoint of another mailbox or folder. |
| `OutboxUnavailable` | | No SMTP configured for this mailbox. |
| `DuplicateMailbox` | `id: MailboxId` | A live mailbox with the same id is registered. |
| `Unexpected` | | Unclassified failure; `cause` holds the original. |

## AuthorizationFailure

| Value | Meaning |
|---|---|
| `UNKNOWN_STATE` | Missing, unknown or already consumed `state`. |
| `EXPIRED` | Pending session older than ten minutes. |
| `REDIRECT_NOT_ALLOWED` | Redirect URI not in the allowlist. |
| `PROVIDER_ERROR` | Provider returned an error or no code. |
| `EXCHANGE_FAILED` | Code exchange failed. |
| `WRONG_ACCOUNT` | Authenticated account differs from the expected email. |
| `STORE_FAILED` | `AuthorizationSessionStore` threw. |

## IntegrityKind

| Value | Meaning |
|---|---|
| `UID_VALIDITY_CHANGED` | The folder's UIDVALIDITY changed; all UIDs are invalid. |
| `MESSAGE_EXPUNGED` | The message disappeared during an operation. |
| `PART_CHANGED` | The MIME part differs from the selected descriptor. |
| `MEMBERSHIP_CHANGED` | A frozen scan's membership became invalid. |

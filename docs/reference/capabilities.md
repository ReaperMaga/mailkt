# Capabilities

The capability interfaces of `Mailbox`, in `dev.reapermaga.mailkt.client`.

## Mailbox

| Member | Description |
|---|---|
| `id: MailboxId` | Provider plus canonical address. |
| `email: MailAddress` | The opened address. |
| `state: StateFlow<MailboxState>` | Authoritative lifecycle state. |
| `events: Flow<MailboxEvent>` | Bounded best-effort diagnostics. |
| `folders: Folders` | Folder operations. |
| `messages: Messages` | Reading and watching. |
| `conversations: Conversations` | Threading. |
| `outbox: Outbox?` | Sending, or `null` without SMTP. |
| `suspend reconnect()` | Resume after re-authorization or from a recoverable `Failed`. |
| `suspend close()` | Idempotent close. |
| `suspend Mailbox.use { }` | Runs a block and always closes. |

## Folders

| Function | Returns | Description |
|---|---|---|
| `list()` | `List<FolderInfo>` | All folders. |
| `special(use)` | `FolderInfo?` | Folder by special-use attribute. |
| `append(folder, draft, flags)` | `MessageLocation?` | Stores a message; location when the server reports it. |

## Messages

| Function | Returns | Description |
|---|---|---|
| `page(selection, limit = 50)` | `EnvelopePage` | One page; pass `page.next` back to continue. |
| `envelopes(selection)` | `Flow<MessageEnvelope>` | Frozen snapshot stream. |
| `messages(selection, maxBytesPerMessage)` | `Flow<MailMessage>` | Full messages; envelope filtering first. |
| `envelope(location)` | `MessageEnvelope` | One envelope. |
| `structure(location)` | `MessageStructure` | MIME tree without content. |
| `download(ref, maxBytes)` | `DownloadedPart` | One part; limit is mandatory. |
| `get(location, maxBytes)` | `MailMessage` | One complete message. |
| `watchEnvelopes(folder, query, from)` | `Flow<WatchedEnvelope>` | Live envelopes with catch-up. |
| `watch(folder, query, from, maxBytesPerMessage)` | `Flow<WatchedMessage>` | Live full messages with catch-up. |

`Messages.DEFAULT_MAX_BYTES` is 25 MiB. `WatchedEnvelope` and `WatchedMessage` pair the item with its `next: WatchCheckpoint`.

## Conversations

| Function | Returns | Description |
|---|---|---|
| `of(location, maxMessages = 200)` | `Conversation` | Conversation containing a message, merged across copies. |
| `synchronize(folder, from, maxMessages = 500, query)` | `ConversationSync` | Incremental changes and next checkpoint. |
| `assemble(envelopes)` | `List<Conversation>` | Offline grouping, no server contact. |

## Outbox

| Function | Returns | Description |
|---|---|---|
| `newDraft()` | `Draft` | Empty draft with `from` and `messageId` set. |
| `reply(original, replyAll = false, text)` | `Draft` | Reply with recipients, subject and threading headers. |
| `send(draft, saveToSent = true)` | `SendResult` | Submits once; never retried. |

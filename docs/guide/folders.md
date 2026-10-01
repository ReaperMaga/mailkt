# Folders

`mailbox.folders` lists folders, resolves special-use folders and appends messages.

```kotlin
interface Folders {
    suspend fun list(): List<FolderInfo>
    suspend fun special(use: SpecialUse): FolderInfo?
    suspend fun append(folder: FolderPath, draft: Draft, flags: MessageFlags = MessageFlags()): MessageLocation?
}
```

## Discovering folders

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Reading.kt#folders{kotlin}

Each `FolderInfo` carries:

| Field | Description |
|---|---|
| `path` | `FolderPath` as reported by the server. Pass it to every other API. |
| `name` | Display name. |
| `specialUse` | `INBOX`, `SENT`, `DRAFTS`, `TRASH`, `JUNK`, `ARCHIVE`, `ALL`, `FLAGGED` or `null`. |
| `selectable` | `false` for pure container folders. |
| `messageCount`, `unseenCount` | When the server reports them. |

## Special-use folders

Folder names differ by provider and locale ("Sent", "Sent Items", "[Gmail]/Gesendet", ...). `special` resolves them by their IMAP special-use attribute instead, and returns `null` if the server has none. Prefer it over hard-coded names.

## Appending

`append` stores a draft or any composed message in a folder, for example to save a draft:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Sending.kt#append-draft{kotlin}

It returns the new `MessageLocation` when the server reports it (UIDPLUS), otherwise `null`.

## In a real app

Folder paths differ per provider and per user language, so resolve them once after opening and keep them with the mailbox. A settings screen can offer the remaining folders as choices:

```kotlin
data class KnownFolders(
    val inbox: FolderPath,
    val sent: FolderPath?,
    val drafts: FolderPath?,
    val archive: FolderPath?,
)

suspend fun resolveFolders(mailbox: Mailbox): KnownFolders = KnownFolders(
    inbox = checkNotNull(mailbox.folders.special(SpecialUse.INBOX)) { "Server reported no inbox" }.path,
    sent = mailbox.folders.special(SpecialUse.SENT)?.path,
    drafts = mailbox.folders.special(SpecialUse.DRAFTS)?.path,
    archive = mailbox.folders.special(SpecialUse.ARCHIVE)?.path,
)

/** DTO for a "which folder should we import from?" dropdown. */
data class FolderOption(val path: String, val name: String, val unread: Int?)

suspend fun folderOptions(mailbox: Mailbox): List<FolderOption> =
    mailbox.folders.list()
        .filter { it.selectable && it.specialUse !in setOf(SpecialUse.TRASH, SpecialUse.JUNK) }
        .map { FolderOption(it.path.value, it.name, it.unseenCount) }
        .sortedBy { it.name.lowercase() }
```

Store the chosen `FolderPath.value` string and wrap it in `FolderPath(...)` again later. If the user renames or deletes the folder, operations throw `MailException.FolderNotFound`, which is your cue to ask again.

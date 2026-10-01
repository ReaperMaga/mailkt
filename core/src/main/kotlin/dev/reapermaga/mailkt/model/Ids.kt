package dev.reapermaga.mailkt.model

/** Validated e-mail address. Comparison is case-insensitive via [normalized]. */
@JvmInline
value class MailAddress(val value: String) {
    init {
        require(value.isNotBlank() && '@' in value && value.none { it.isWhitespace() }) { "Invalid mail address" }
    }

    val normalized: String get() = value.trim().lowercase()

    /** Redacted so accidental string interpolation does not leak addresses into logs. */
    override fun toString(): String = "MailAddress(***)"
}

/** An address with an optional display name. */
data class MailParticipant(val address: MailAddress, val displayName: String? = null) {
    override fun toString(): String = "MailParticipant(***)"
}

/**
 * Identity of one mailbox: provider identity plus provider-canonical authenticated address.
 * The same address on different providers/registrations never collides.
 */
data class MailboxId(val provider: String, val canonicalAddress: String) {
    init {
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(canonicalAddress.isNotBlank()) { "canonicalAddress must not be blank" }
    }

    /** Stable storage key; contains the address, so never log it. */
    val storageKey: String get() = "$provider:${canonicalAddress.lowercase()}"

    override fun toString(): String = "MailboxId(provider=$provider)"

    companion object {
        fun of(provider: String, email: MailAddress): MailboxId = MailboxId(provider, email.normalized)
    }
}

/** Key under which a [dev.reapermaga.mailkt.client.TokenStore] persists one mailbox's token cache. */
data class TokenKey(val provider: String, val clientRegistration: String, val mailboxId: MailboxId) {
    val storageKey: String get() = "$provider/$clientRegistration/${mailboxId.storageKey}"

    override fun toString(): String = "TokenKey(provider=$provider, client=$clientRegistration)"
}

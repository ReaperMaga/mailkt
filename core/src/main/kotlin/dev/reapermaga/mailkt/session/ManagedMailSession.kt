package dev.reapermaga.mailkt.session

import java.time.Instant
import java.util.concurrent.CompletableFuture

class ManagedMailSession(
    val session: MailSession,
    var lastKeepAliveCheck: Instant = Instant.now(),
    val connectionCallback: (session: MailSession) -> CompletableFuture<MailConnection>
)
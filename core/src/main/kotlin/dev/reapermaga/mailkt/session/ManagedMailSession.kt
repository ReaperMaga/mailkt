package dev.reapermaga.mailkt.session

import java.time.Instant
import java.util.concurrent.CompletableFuture

class ManagedMailSession(
    val session: MailSession,
    var lastKeepAliveCheck: Instant = Instant.now(),
    var lastConnection: MailConnection,
    val connectionProvider: (session: MailSession) -> CompletableFuture<MailConnection>
) {
    var currentReconnectAttempt: Int = 0
    val lifecycle = ManagedMailSessionLifecycle()
}

class ManagedMailSessionLifecycle {

    val connection = mutableListOf<LifecycleSubscriber<MailConnection>>()
    val keepAlive = mutableListOf<LifecycleSubscriber<Unit>>()
}

fun interface LifecycleSubscriber<T : Any> {
    fun onEvent(event: T)
}
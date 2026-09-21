package dev.reapermaga.mailkt.internal.connection

import dev.reapermaga.mailkt.internal.transport.TransportConnection

/** A published connection pinned to its generation. Operations hold this for their whole duration. */
internal class Lease(val generation: Long, val connection: TransportConnection)

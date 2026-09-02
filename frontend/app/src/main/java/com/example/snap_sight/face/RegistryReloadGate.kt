package com.example.snap_sight.face

/** Generation gate for atomically publishing the paired face/object registry snapshot. */
internal class RegistryReloadGate {
    data class Token internal constructor(internal val generation: Long)

    private val lock = Any()
    private var generation = 0L

    @Volatile
    var isReady: Boolean = false
        private set

    fun begin(): Token = synchronized(lock) {
        isReady = false
        Token(++generation)
    }

    /** Returns false when a newer reload superseded [token]. */
    fun complete(token: Token, success: Boolean, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (token.generation != generation) return@synchronized false
            if (success) publish()
            isReady = success
            true
        }
}

package com.example.snap_sight.cv

/**
 * Immutable target-intent snapshot shared by the analysis and UI threads.
 *
 * [generation] is the identity of the intent episode. It deliberately is not
 * derived from [spec] equality: starting a new session with an equal spec must
 * still invalidate output that was produced for the previous session.
 */
internal data class TargetIntentSnapshot(
    val spec: TargetSpec?,
    val identityName: String?,
    val generation: Long,
)

/** Serializes target spec/name changes into one versioned snapshot. */
internal class TargetIntentState {
    private val lock = Any()

    @Volatile
    private var snapshot = TargetIntentSnapshot(
        spec = null,
        identityName = null,
        generation = 0L,
    )

    fun current(): TargetIntentSnapshot = snapshot

    fun isCurrent(generation: Long): Boolean = snapshot.generation == generation

    fun set(
        spec: TargetSpec?,
        identityName: String?,
        forceNewGeneration: Boolean = false,
    ): TargetIntentSnapshot = synchronized(lock) {
        val previous = snapshot
        if (!forceNewGeneration && previous.spec == spec && previous.identityName == identityName) {
            return@synchronized previous
        }
        TargetIntentSnapshot(
            spec = spec,
            identityName = identityName,
            generation = previous.generation + 1L,
        ).also { snapshot = it }
    }

    fun setSpec(spec: TargetSpec?): TargetIntentSnapshot = synchronized(lock) {
        val previous = snapshot
        if (previous.spec == spec) return@synchronized previous
        previous.copy(spec = spec, generation = previous.generation + 1L)
            .also { snapshot = it }
    }

    fun setIdentityName(identityName: String?): TargetIntentSnapshot = synchronized(lock) {
        val previous = snapshot
        if (previous.identityName == identityName) return@synchronized previous
        previous.copy(identityName = identityName, generation = previous.generation + 1L)
            .also { snapshot = it }
    }
}

package com.example.snap_sight.face

/**
 * 등록 DB commit과 취소/새 등록 사이의 선형화 지점.
 *
 * [commitIfCurrent]는 token이 아직 현재 세대일 때만 commit을 실행하며 실행하는 동안 gate를 잡는다.
 * 따라서 [cancel]이 먼저 선형화되면 DB 쓰기가 시작되지 않고, commit이 먼저 시작됐다면 cancel은 그
 * 쓰기가 끝난 뒤 반환한다. 오래된 watchdog/commit thread도 새 등록 세대에는 영향을 주지 못한다.
 */
enum class EnrollmentCancelResult {
    /** Cancellation linearized before persistent storage began. */
    CANCELLED,
    /** Persistent storage already completed, so cancellation cannot claim success. */
    COMPLETED,
    /** This identifier had no active enrollment or undelivered completion. */
    NOT_ACTIVE,
}

internal class EnrollmentCommitGate {
    class Token internal constructor(internal val generation: Long)

    private enum class State { PENDING, COMMITTING, COMMITTED }

    private data class Entry(
        val token: Token,
        var state: State = State.PENDING,
    )

    private val lock = Any()
    private var generation = 0L
    private var current: Entry? = null

    fun begin(): Token = synchronized(lock) {
        Token(++generation).also { current = Entry(it) }
    }

    fun cancel(): EnrollmentCancelResult = synchronized(lock) {
        val entry = current ?: return@synchronized EnrollmentCancelResult.NOT_ACTIVE
        when (entry.state) {
            State.PENDING -> {
                current = null
                EnrollmentCancelResult.CANCELLED
            }
            State.COMMITTING,
            State.COMMITTED -> {
                // commitIfCurrent owns this same lock through the DB write. A caller can
                // observe COMMITTED only after persistence has linearized.
                current = null
                EnrollmentCancelResult.COMPLETED
            }
        }
    }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { current?.token === token }

    fun commitIfCurrent(token: Token, commit: () -> Unit): Boolean = synchronized(lock) {
        val entry = current
        if (entry?.token !== token || entry.state != State.PENDING) return@synchronized false
        entry.state = State.COMMITTING
        try {
            commit()
            entry.state = State.COMMITTED
            true
        } catch (t: Throwable) {
            if (current === entry) current = null
            throw t
        }
    }

    /** Lets completion delivery and cancellation expose exactly one UI outcome. */
    fun takeCompletion(token: Token): Boolean = synchronized(lock) {
        val entry = current
        if (entry?.token !== token || entry.state != State.COMMITTED) return@synchronized false
        current = null
        true
    }
}

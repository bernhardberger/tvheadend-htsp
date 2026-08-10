package at.bernhardberger.tvheadend.htsp

import kotlinx.coroutines.Job

internal data class `AttemptOwnedJobReplacement-internal`(
    val accepted: Boolean,
    val previous: Job?,
)

internal typealias AttemptOwnedJobReplacement = `AttemptOwnedJobReplacement-internal`

internal data class `AttemptOwnedJobHandoff-internal`<T>(
    val value: T,
    val previous: Job?,
)

internal typealias AttemptOwnedJobHandoff<T> = `AttemptOwnedJobHandoff-internal`<T>

/**
 * Owns one job whose installation must be atomic with an external attempt state.
 * [isCurrentAttemptLocked] is invoked only while [lock] is held, so the attempt
 * cannot change between validation and replacement.
 */
internal class `AttemptOwnedJobSlot-internal`(
    private val lock: Any,
    private val isCurrentAttemptLocked: (Long) -> Boolean,
) {
    private var job: Job? = null

    fun replaceIfCurrent(attemptId: Long, candidate: Job): AttemptOwnedJobReplacement =
        synchronized(lock) {
            if (!isCurrentAttemptLocked(attemptId)) {
                AttemptOwnedJobReplacement(accepted = false, previous = null)
            } else {
                AttemptOwnedJobReplacement(accepted = true, previous = job).also {
                    job = candidate
                }
            }
        }

    fun detach(): Job? = synchronized(lock) {
        job.also { job = null }
    }

    /** Mutates the owning attempt and detaches its job as one lock-protected handoff. */
    fun <T> updateAttemptAndDetach(updateAttemptLocked: () -> T): AttemptOwnedJobHandoff<T> =
        synchronized(lock) {
            AttemptOwnedJobHandoff(
                value = updateAttemptLocked(),
                previous = job,
            ).also {
                job = null
            }
        }

    fun clearIfSame(candidate: Job) {
        synchronized(lock) {
            if (job === candidate) job = null
        }
    }
}

internal typealias AttemptOwnedJobSlot = `AttemptOwnedJobSlot-internal`

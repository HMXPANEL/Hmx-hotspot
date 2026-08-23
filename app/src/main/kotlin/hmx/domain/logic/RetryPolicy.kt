package hmx.domain.logic

/** Bounded retry policy with exponential backoff; pure logic so it is unit-testable. */
class RetryPolicy(val maxAttempts: Int = 3) {
    init { require(maxAttempts >= 1) { "maxAttempts must be >= 1" } }

    /** Exponential backoff: 2s, 4s, 8s... capped at 30s. */
    fun backoffMs(attempt: Int): Long {
        require(attempt in 1..maxAttempts)
        return (2000L shl (attempt - 1)).coerceAtMost(30_000L)
    }
}

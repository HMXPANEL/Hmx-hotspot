package hmx.domain.logic

/**
 * Phase 10 duplicate-start protection: pure, unit-testable predicates that
 * decide whether a lifecycle action is valid from the current state.
 */
object LifecycleGuards {
    /** Starting sharing is legal only from an idle or failed provider. */
    fun canStartSharing(state: ProviderState): Boolean = when (state) {
        is ProviderState.Idle, is ProviderState.Failed -> true
        else -> false
    }

    /** New pairing codes only make sense while actively advertising. */
    fun canRegenerateCode(state: ProviderState): Boolean = state is ProviderState.Advertising

    /** Scanning again while a scan/claim is already in flight would double-claim. */
    fun canStartScan(state: ClientState): Boolean = when (state) {
        is ClientState.Idle, is ClientState.Failed, is ClientState.Disconnecting -> true
        else -> false
    }
}

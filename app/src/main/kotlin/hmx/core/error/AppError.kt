package hmx.core.error

enum class AppError(
    val title: String,
    val explanation: String,
    val actionLabel: String,
) {
    NO_INTERNET(
        "No internet",
        "This device has no working internet connection right now. The tunnel needs its own connection to reach the provider.",
        "Open network settings",
    ),
    PROVIDER_OFFLINE(
        "Provider offline",
        "The sharing device cannot be reached. It may have lost internet, closed the app, or gone to sleep.",
        "Retry",
    ),
    PAIRING_EXPIRED(
        "Pairing expired",
        "This pairing code is no longer valid. Codes last five minutes for safety.",
        "Get a new code",
    ),
    PAIRING_REJECTED(
        "Pairing rejected",
        "The provider declined this pairing request.",
        "Back",
    ),
    VPN_PERMISSION_DENIED(
        "VPN permission needed",
        "Android requires your approval before HMX can route traffic through a VPN interface.",
        "Try again",
    ),
    HANDSHAKE_FAILED(
        "Tunnel handshake failed",
        "The secure link to the provider could not be established. A direct path may be blocked between the two networks.",
        "Retry via relay",
    ),
    TUNNEL_FAILED(
        "Tunnel error",
        "The VPN tunnel stopped unexpectedly.",
        "Restart tunnel",
    ),
    PROBE_FAILED(
        "Connected, but no internet",
        "The tunnel is up, but test requests through it are not getting answers. The provider's own connection is likely down.",
        "Run diagnostics",
    ),
    DNS_FAILURE(
        "Name lookup failed",
        "Addresses are not resolving through the tunnel.",
        "Run diagnostics",
    ),
    RELAY_UNAVAILABLE(
        "Relay unavailable",
        "Neither a direct path nor the fallback relay could be reached.",
        "Retry",
    ),
    TIMEOUT(
        "Connection timed out",
        "The operation took too long and was abandoned.",
        "Retry",
    ),
    PROVIDER_STOPPED(
        "Sharing ended",
        "The provider stopped sharing its internet.",
        "OK",
    ),
    DISCONNECTED_BY_PEER(
        "Disconnected",
        "The other device ended the session or revoked this device.",
        "OK",
    ),
    NETWORK_CHANGED(
        "Network changed",
        "Your connection switched networks. Reconnecting the tunnel.",
        "Dismiss",
    ),
    SERVICE_STOPPED(
        "Background service stopped",
        "Android stopped HMX in the background. Reconnecting now.",
        "Reconnect",
    ),
    CRASH_RECOVERED(
        "Session restored",
        "HMX restarted after an interruption and restored your previous session state.",
        "Continue",
    ),
    DATA_LIMIT_REACHED(
        "Data limit reached",
        "The sharing session used its configured data allowance. Forwarding stopped.",
        "Start again",
    ),
    CONNECTION_LOST(
        "Connection lost",
        "The tunnel dropped and automatic reconnection did not succeed.",
        "Retry",
    ),
    UNKNOWN(
        "Something went wrong",
        "An unexpected error occurred.",
        "Retry",
    ),
}

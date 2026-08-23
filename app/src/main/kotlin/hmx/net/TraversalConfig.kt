package hmx.net

/** Deployment-specific traversal endpoints. Override per environment. */
object TraversalConfig {
    /** Public UDP relay address "host:port". */
    var relayAddress: String = "127.0.0.1:51821"
}

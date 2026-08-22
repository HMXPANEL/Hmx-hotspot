# TESTING.md

## Layers

| Layer | Where | Runs |
|---|---|---|
| Go unit/loopback | `gateway-native/*_test.go` | anywhere (host, CI) |
| Go online e2e | `gateway-native/cmd/harness` | host with internet; device-gated items marked inside report |
| Kotlin JVM unit tests | `app/src/test` | CI (`testDebugUnitTest`) |
| Instrumented (2 devices) | future `connectedDebugAndroidTest` | Phases 4+ |

## Current coverage

**Go (Phase 0, all passing):**
- TCP loopback through full tunnel + accounting sanity
- UDP round-trip through forwarder mapping
- DNS relay payload integrity
- Hard-limit cuts flows and sets the flag
- Wrong-key peer cannot handshake

**Kotlin JVM (Phase 1–2):**
- `ProviderMachineTest` — idle→preparing→advertising→peer approval→stats→stop→failed/reset transitions
- `ClientMachineTest` — happy path, VPN-denied typed failure, reconnect/recovered clock semantics, disconnect
- `PairingCodeTest` — generation validity, confusable normalization (O/I/L), length rules, 5-minute expiry window
- `HmxLogTest` — key redaction, hex fingerprint truncation, untouched normal text
- `DataLimitsTest` — warning/exceeded thresholds, zero-limit bypass, byte/duration formatting

## Mock UI scenario matrix (Phase 2, via Home → debug)

HAPPY_PATH · PAIRING_EXPIRED · PAIRING_REJECTED · VPN_DENIED · HANDSHAKE_FAIL · PROBE_FAIL · PROVIDER_OFFLINE · FORCE_NETWORK_CHANGE

Every error route renders its dedicated screen with explanation + actions.

## Pending until hardware (from PHASE_0_REPORT §4)

VpnService capture on real Phone B · gateway AAR on real Phone A · Chrome/YouTube/Play Store full-device tests · direct-connectivity matrix · battery/thermal soak.

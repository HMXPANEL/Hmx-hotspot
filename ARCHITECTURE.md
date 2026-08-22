# ARCHITECTURE.md — HMX Remote Internet

Companion to **PLANNING.md** (source of truth) and **PHASE_0_REPORT.md** (feasibility evidence). This document describes what is *actually built* after Phase 2 and flags every deliberate deviation.

## Big picture

```
Phone B (user)                          Phone A (provider)
┌──────────────────────────┐            ┌──────────────────────────────┐
│ :app  Kotlin + Compose   │            │ :app  Kotlin + Compose       │
│ ClientMachine (StateFlow)│            │ ProviderMachine (StateFlow)  │
│ HmxVpnService  (Phase 5) │◄═ WG UDP ═►│ hmx-gateway.aar (Go)         │
│ official wg tunnel lib   │ direct or  │  wireguard-go + gVisor       │
│ (Phase 4+)               │ via relay  │  forwarders → host sockets   │
└───────────┬──────────────┘            └──────────────┬───────────────┘
            │      control plane (Phase 3+, Supabase)  │
            └──── pairing · signaling · registry ──────┘
```

## Module layout (as implemented)

- **`:app`** — single Android module, package `hmx.*`:
  - `ui/theme` — dark-first Material 3 identity (neon-green accent reserved for live state).
  - `navigation` — single-activity Navigation Compose graph (`Routes`), bottom bar on Home/Devices/Activity/Settings.
  - `ui/screens/*` — all Phase 2 screens incl. error routes.
  - `ui/components` — shared composables (StatusPill, StateOrb, MetricCard, CodeDisplay, QrImage via ZXing, ErrorStateView, EmptyStateView, SkeletonBlock, DataUsageBar).
  - `domain/logic` — `ProviderMachine`, `ClientMachine` (sealed state hierarchies over `StateFlow`; no boolean state soup), `DataLimits`.
  - `domain/model` — pure models (Device, Session, TrafficStats, HmxSettings…).
  - `data/local` — `SettingsRepository` on DataStore Preferences.
  - `security` — `PairingCode` (Crockford base32, 8 chars, 5-min TTL), `PairingCodeInfo`, `SecretVault` interface with `KeystoreVault` (AES-GCM envelope under AndroidKeyStore master key).
  - `core/error` — `AppError` taxonomy mirroring PLANNING §21 (title/explanation/action per error).
  - `core/logging` — `HmxLog` with mandatory redaction of key-like material.
  - `mock/MockHmxEngine` — drives both machines through scripted flows; injectable failure scenarios for UI testing.
- **`gateway-native/`** — Go module: wireguard-go + gVisor netstack userspace gateway. Phase 0-proven (see PHASE_0_REPORT). Built to an AAR in CI (`gateway-native.yml`).

## Deviations from PLANNING.md (documented, intentional)

| Planning said | Implemented | Why |
|---|---|---|
| Hilt DI | Manual `AppContainer` + CompositionLocal accessor | Zero local build capability in this environment; manual wiring removes processor/toolchain risk until CI is green. Migration path is mechanical. |
| Room | Deferred | No persistence requirement yet beyond settings (mock history lives in memory). Room lands with real session persistence (Phase 3+). |
| Per-screen ViewModels everywhere | State hoisted from engine/machine StateFlows directly into screens | The machines already are the observable state holders; VMs arrive when lifecycle-aware real repositories exist. |
| ML Kit scanner | Mock scanner screen | Camera work is meaningless without devices here; screen + route exist and are wired. |

None of these change the architecture contract — they sequence it.

## State machines

See `ProviderMachine.kt` / `ClientMachine.kt`. Transitions are event methods that validate the current state before mutating; illegal transitions are no-ops (logged in later phases). UI observes via `collectAsState()` and never holds connection truth itself.

## Build

CI-only by design right now (no local Android SDK): `.github/workflows/android.yml` runs unit tests + `assembleDebug` on JDK 17 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21. Gateway AAR builds via `gateway-native.yml` with NDK 27.

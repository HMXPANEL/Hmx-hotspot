# PHASE_3A + 3B REPORT

## 3A Supabase control plane — PASS
- Reset: old experimental impl removed per SUPABASE_RESET_REPORT.md (12 tables, 6 fns, enum, triggers, 2 auth users; backup snapshot archived in report).
- Fresh: devices / pairing_sessions / pairing_requests / peers / sessions / device_events, RLS via auth.uid(), security-definer RPCs only.
- Identity: GoTrue user per device (email=<id>@devices.hmx.internal, password=device secret); edge fn `hmx-auth` register/token. Raw secret only over TLS; server keeps sha256 anchor. No JWT-secret dependency.
- Live e2e (real HTTP vs production project): **19/19 PASS** — register/mint, create/claim/respond/approve/reject, inner-IP allocation+reactivation, tunnel-session rows, revoke propagation to peer view, RLS deny (B↛provider sessions 403, anon 401), invalid code, self-pair block, CONSUMED reuse, server-side EXPIRED.
- Bugs found & fixed during testing: pairing_requests CHECK missing 'expired'; plpgsql FOR-var NULL-after-loop (octet allocator); claim/pending used stale JWT claim instead of auth.uid().
- Realtime: intentionally deferred — polling of real server state used instead (documented).

## 3A Android — PASS (CI)
Mocks REMOVED from production paths: MockHmxEngine deleted; debug scenario drawer deleted; "[mock] peer scans QR" deleted; fake Pixel-8/history/stats deleted.
New real stack: ControlClient (OkHttp), IdentityManager (real WG Curve25519 keys via gateway AAR keygen, Keystore envelope storage), RealEngine driving the same Provider/Client state machines from live RPCs, real QR scanner (CameraX+ZXing decoding actual payloads), real VpnService.prepare() flow, real peers list / revoke / session history from control plane.

## 3B WireGuard foundation — PARTIAL
- REAL key generation on device (gateway-native keygen) ✔
- Private keys local-only (vault), pubkeys registered ✔
- Peer config builder emits valid wg-quick (keys, inner IPs, MTU 1280, keepalive 25) ✔
- Tunnel bring-up via official GoBackend VpnService (declared, prepare() flow wired) ✔ code-complete
- GatewayEngineHost starts the proven Phase-0 userspace gateway AAR on Phone A with real keys/port ✔ code-complete
- Handshake verification reads REAL Statistics (latestHandshakeEpochMillis) and records a real session row ✔ code-complete

### NOT TESTED — REQUIRES TWO PHYSICAL DEVICES
Handshake between phones, endpoint reachability, NAT behavior. CI cannot exercise VPN interfaces. Host-side equivalent already proven (PHASE_0: full WG handshake + traffic through identical engine).

## Honest boundaries (NOT YET by design)
Full-device internet routing · Chrome/YouTube tests · provider→internet forwarding path · traffic counters surfaced in UI · relay/VPS · auto-reconnect production logic → later phases. UI shows real states only; unimplemented capabilities are not simulated anywhere.

## CI
android.yml GREEN @42fcbbb (tests+APK). gateway-native.yml GREEN (unchanged engine).

## Provider "Preparing…" hang — ROOT CAUSE & FIX (post-device report)
- Root cause: any exception in RealEngine.startSharing (identity init, registration, session RPC) set ProviderState.Failed, but PairingScreen's `else` branch rendered Failed as the preparing skeleton forever — no error navigation, no timeout. Likely on-device trigger: RPC args sent as JSON strings for int/bool params.
- Fix: PairingScreen Failed → dedicated error screen; withTimeout(30–45s) → TIMEOUT state; jsonArgs emits native int/bool; safe HTTP/RPC status logging added.
- Files: RealEngine.kt, ControlClient.kt, ProviderScreens.kt, HmxNavHost.kt.
- CI: GREEN @ebd3d76 (tests+APK). Device retest pending owner; 3B handshake still NOT TESTED.

## BAD_SECRET root cause (real-device log)
ControlClient.register() sent legacy `secret_hash` field; deployed hmx-auth v3 expects raw `secret` over TLS (GoTrue password). Empty secret → regex fail → BAD_SECRET → identity/init failed → Failed state (now surfaced to error screen, not Preparing).
Fix: send `"secret"` directly (contract check vs live function: 200 + device_id). CI GREEN @3114ddf.

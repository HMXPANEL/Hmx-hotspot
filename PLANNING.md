# HMX Remote Internet — Planning & Architecture Document

**Version:** 1.0-draft · **Date:** 2026-08-21 · **Status:** FOR REVIEW — implementation may not begin until this document is approved.

> **Golden rule of this project:** *A successful WireGuard handshake does NOT mean internet sharing works.* The feature is complete only when Phone B's normal apps reach the internet through Phone A's connection (see §18).

---

## 1. Executive Summary

HMX Remote Internet lets one Android phone (Provider, "Phone A") share its live 4G/5G/Wi-Fi internet with another Android phone (User, "Phone B") remotely, over a WireGuard tunnel, with QR-code pairing and no manual configuration.

**Feasibility verdict:** Achievable on stock, unrooted Android — with one non-obvious architectural decision that this plan hinges on:

- **Phone B (client side)** is straightforward: embed the official WireGuard Android tunnel library (`com.wireguard.android:tunnel`, Apache-2.0) inside an `VpnService` to capture all device traffic. Proven, low-risk.
- **Phone A (provider side)** is the hard part. An unrooted phone cannot do kernel-level IP forwarding or NAT (needs root iptables/ip_forward), and the official library alone would dump decrypted packets into a dead-end TUN interface. **Solution: Phone A runs a userspace gateway** — a custom Go module (wireguard-go + gVisor netstack, the same proven pattern behind `wireproxy` and Rethink DNS/Firestack) compiled via gomobile into an AAR. It terminates WireGuard entirely in userspace and re-originates every flow as normal sockets from Phone A itself. Phone A's routing table is never touched.

**Highest risks (in order):**
1. Userspace gateway throughput/CPU/battery on Phone A — UNKNOWN—NEEDS PROTOTYPE (Phase 0).
2. Direct connectivity odds between two carrier-CGNAT phones are poor; a relay fallback is likely required often, and relay bandwidth costs money.
3. Long-running background reliability across OEM battery managers (Xiaomi/Samsung aggro).

**Recommended MVP:** single-user pair of devices, QR pairing via a managed Supabase control plane, direct connection with automatic relay fallback, full-device internet on B, provider-side enforceable data limits. Personal/sideload distribution; no Play Store constraints assumed.

---

## 2. Product Definition

**Product:** A private-use Android app with two roles on two phones.

| Role | Name | Has | Does |
|---|---|---|---|
| Provider | Phone A | Live 4G/5G/Wi-Fi | Runs WireGuard endpoint + userspace gateway; serves internet |
| User | Phone B | Any connectivity (needed only to reach A) | Routes all device traffic through tunnel to A |

**Traffic contract (the entire product):**

```
Chrome / YouTube / Instagram / WhatsApp / Play Store / any app on B
        ↓
Android VPN interface (VpnService TUN on B)
        ↓
WireGuard (UDP, encrypted)          [direct, or via relay]
        ↓
Userspace gateway on A (netstack terminates flows)
        ↓
Normal sockets on A
        ↓
A's 4G/5G/Wi-Fi
        ↓
Internet
```

**Non-goals (v1):** payments, ads, bandwidth selling, marketplace, crypto, multi-tenant accounts, iOS, commercial SLAs. Personal/private use only.

---

## 3. User Personas / Roles

| Persona | Description | Needs |
|---|---|---|
| Solo owner (primary) | Same person owns both phones; e.g., home phone has fiber, travel phone needs internet | Dead-simple pairing once, then one-tap connect |
| Trusted pair | Two people who trust each other (family) | Revocation, visibility ("who is connected"), data cap |

There is no anonymous third party. Every user is someone the provider personally authorized. This assumption simplifies security substantially (no reputation, no escrow, no ratings).

---

## 4. Complete User Flow

### First-run (both phones)
1. Splash → Welcome → permission pre-explanations (VPN, notifications, battery) → choose role (can be changed later in Home).

### Provider (Phone A)
2. Share dashboard → **Start Sharing**
3. System VPN permission dialog appears **once** (A runs a VpnService-less gateway, so strictly A does NOT need VPN permission — see §11 note; we still run a foreground service)
4. Sharing-active screen: pairing code (8 chars) + QR, countdown timer (5 min), connected-devices list updates live
5. Peer connects → status flips to CONNECTED; session counters start
6. Stop → confirmation dialog → teardown, session logged

### User (Phone B)
7. Use dashboard → **Scan QR** (or type code)
8. Device found (name, last seen) → **Connect**
9. VPN permission dialog → Connecting (handshake → probe) → Connected
10. Status pill visible; diagnostics page available
11. Disconnect → confirmation → teardown

### Failure branches (each has a dedicated screen/state, §21)
Wrong/expired code · provider offline · VPN denied · handshake timeout · probe fail (connected-but-no-internet) · provider revoked mid-session · B loses transport network · provider loses uplink · process killed by OEM.

---

## 5. Screen Map

Single-activity, Navigation Compose. Route names in parentheses.

```
Splash (splash)
Welcome (welcome)                        Role pick embedded here
Home (home)                              Hub: current role card + recent sessions

Provider stack
├─ Share Dashboard (provider/dashboard)
├─ Permission Explanation (provider/permissions)
├─ Pairing Active (provider/pairing)     code + QR + timer
├─ Sharing Active (provider/sharing)     peers, counters, stop
├─ Connected Devices (provider/devices)
└─ Stop Confirmation (dialog)

User stack
├─ Use Dashboard (user/dashboard)
├─ Enter Code (user/code)
├─ QR Scanner (user/scanner)
├─ Device Found (user/found)
├─ Connect Confirm (user/confirm)
├─ VPN Permission Explanation (user/vpnperm)
├─ Connecting (user/connecting)
├─ Connected (user/connected)
└─ Disconnect Confirmation (dialog)

Management
├─ Devices (devices)                     paired device registry
├─ Device Details (devices/{id})
├─ Activity (activity)                   live counters
├─ Session History (history)
├─ Settings (settings)
├─ Security (settings/security)
├─ Data Limits (settings/limits)
├─ Notifications (settings/notifications)
├─ Diagnostics (diagnostics)
└─ About (about)

Error states                             full-screen composables, not toasts:
ErrorNoInternet · ErrorProviderOffline · ErrorPairingExpired · ErrorPairingRejected ·
ErrorVpnDenied · ErrorTunnelFailed · ErrorHandshakeFailed · ErrorProbeFailed ·
ErrorDnsFailure · ErrorRelayUnavailable · ErrorTimeout · ErrorProviderStopped ·
ErrorDisconnectedByPeer · ErrorNetworkChanged · ErrorServiceStopped · ErrorRecoveredBanner
```

31 screens + 16 error states. All reachable from a debug navigation drawer in Phase 2 (mock mode).

---

## 6. Screen-by-Screen UX Plan

| Screen | Purpose | Key elements | States wired |
|---|---|---|---|
| Splash | Brand beat + session restore check | Logo pulse ≤600ms | resumes persisted role/session |
| Welcome | Value prop + role choice | 2 large cards: Share / Use | IDLE |
| Home | Hub | Role switcher, connect button, status pill, last session | all top-level states |
| Share Dashboard | Pre-flight | uplink type badge, data-limit warning if metered | PREPARING |
| Permissions | Explain before asking | VPN/notification/battery rationale cards | → system dialogs |
| Pairing Active | Show code+QR | 8-char code, QR, 5:00 ring countdown, regenerate | PAIRING, expiry → ErrorPairingExpired |
| Sharing Active | Live monitor | peer rows (name, IP, rx/tx sparkline), total shared, Stop FAB | CONNECTED/DISCONNECTING |
| Connected Devices | Registry | revoke, rename, last seen | — |
| Use Dashboard | Entry | Scan / enter code buttons, saved provider shortcut | IDLE |
| Enter Code | Manual fallback | 8-slot code field, paste, autocomplete from clipboard w/ consent | — |
| QR Scanner | Camera scan | viewfinder, torch toggle | malformed → inline error |
| Device Found | Trust checkpoint | provider name/key fingerprint (first 8 hex) | AUTHENTICATING |
| Connect Confirm | Final yes | route-all warning, estimated impact | — |
| VPN Perm | Explain dialog purpose | why Android asks, what happens if denied | VPN_PERMISSION_REQUIRED |
| Connecting | Progress | steps checklist: handshake → address → DNS → probe | STARTING_TUNNEL→CONNECTING |
| Connected | Success | duration, session counters, speed peek, Diagnostics link | CONNECTED |
| Devices | Paired list | role badges, online dot | — |
| Device Details | One device | keys fingerprint, sessions, revoke | revocation flow |
| Activity | Live meters | down/up, session graph | CONNECTED |
| History | Past sessions | date, duration, bytes, mode (direct/relay) | empty/error/loading variants |
| Settings | Preferences | device name, theme, autostart | — |
| Security | Keys & trust | fingerprints, rotate keys, revoke all | — |
| Data Limits | Caps | daily limit slider, warning %, hard-limit toggle + clear copy: "enforced on provider" | — |
| Notifications | Toggles | peer connect/disconnect, limit warnings | — |
| Diagnostics | Dev-grade | handshake age, endpoint, MTU, mode, RTT probe, log export | all error states surface here |
| About | Legal/version | licenses incl. WireGuard attribution | — |

Design direction (applies globally): dark-first Material 3, near-black surfaces (#0A0C0A family), neon-green accent used ONLY for live/connection semantics, Inter-style geometric sans (system font stack acceptable; variable font optional), tabular numerals for counters, restrained motion (state transitions 200–250ms, one hero animation max per screen: the connection orb). No glassmorphism, no gradients except a single subtle accent wash on the hero state indicator.

---

## 7. Technical Architecture

Three planes, strictly separated:

```
CONTROL PLANE (Supabase: Postgres + Realtime + Edge Functions)
    device registry · pairing codes · signaling (endpoints, punch coordination,
    relay hints) · revocation · session metadata. Never carries user traffic.

DATA PLANE (P2P or relayed WireGuard UDP)
    B ⇄ (relay) ⇄ A. Encrypted end-to-end between device keys; relay sees
    ciphertext + inner-IP headers only (if L3 hop), never plaintext.

PRESENTATION/APP PLANE (single :app module, Kotlin + Compose)
    UI → ViewModel → Repository → {ControlClient, TunnelController, GatewayService}
```

Runtime components per role:

| Component | Phone A (provider) | Phone B (user) |
|---|---|---|
| Foreground service | `GatewayService` (holds WG listener alive) | `HmxVpnService` (extends VpnService) |
| Crypto/tunnel engine | `hmx-gateway` AAR (wireguard-go + netstack) | official `com.wireguard.android:tunnel` (GoBackend) |
| Flow translation | netstack → real sockets (userspace NAT) | none needed (kernel TUN does it) |
| Control client | Supabase client (signaling channel open while sharing) | same |
| Local store | Room (sessions, devices) + DataStore (settings) + Keystore-wrapped secrets | same |

Why each major module exists (justification, not ceremony):
- **ui/navigation** — single-activity Compose nav; nothing else hosts UI.
- **domain/model** — pure Kotlin models shared by both roles; keeps Room entities out of ViewModels.
- **data/local** — Room + DataStore; survives process death (§22 recovery depends on it).
- **control** — all Supabase interaction isolated here so the backend can be swapped (e.g., self-host FastAPI later) without touching UI or tunnel code.
- **tunnel/client** — wraps official WG library behind our own `TunnelController` interface; the ONLY module importing `com.wireguard.android`.
- **gateway/provider** — owns the hmx-gateway AAR lifecycle, stats aggregation, limit enforcement hooks, foreground service.
- **security** — key generation/storage/pairing crypto; nothing else touches keys.
- **core** — logging (never logs secrets/IPs at INFO), error taxonomy, dispatchers.

---

## 8. Jetpack Compose Architecture

- **Pattern:** MVVM with unidirectional data flow. Each screen ViewModel exposes ONE sealed `UiState` via `StateFlow` and an events function; Compose renders state, calls intent lambdas. No booleans-in-ViewModel soup (mandated §11 of prompt satisfied by state machines in §20).
- **Observation:** `collectAsStateWithLifecycle()`; services communicate to ViewModels through repositories exposing StateFlows — never direct binder handles in composables.
- **DI:** Hilt (KSP). Justification: coding-agent-friendly conventionality outweighs manual-DI purity; KSP build stays fast.
- **Navigation:** Navigation Compose, typed route builders kept trivial (string routes fine for v1).
- **Theming:** Material3 color scheme generated from a small token set (dark-first; light theme exists but is secondary). Typography scale customized once.
- **Async:** Coroutines + Flow everywhere; `Dispatchers` injected via qualifiers for testability.
- **Animation rules:** animate state-pill color/icon morph, QR fade, connecting checklist stagger. Nothing else moves.

---

## 9. Networking Architecture

### Addressing (inner tunnel network)
| Item | Value | Notes |
|---|---|---|
| Inner IPv4 | `10.66.x.0/24`, A=`.1`, B=`.2` | per-session subnet derived from pairing id (avoids stale-route collisions) |
| Inner IPv6 | `fd00:484d:58xx::/64` ULA | B gets ::2, A ::1 |
| MTU | **1280** default (configurable in Diagnostics) | safest across CGNAT/encapsulation stacks; WG overhead 60B(v4)/80B(v6) leaves ≥1200 usable |
| Keepalive | PersistentKeepalive **25s** from B toward A; A→B too while sharing | keeps NAT mappings + enables roaming |
| Transport port | random per session (ephemeral), exchanged via control plane | reduces blocking; fixed-port fallback option |

### Modes
1. **DIRECT** — B sends WG UDP straight to A's observed endpoint(s).
2. **RELAY** — both peers point at VPS hub (see §14). Selected automatically after failed direct probes, manually forced in Diagnostics.

### DNS
- B's VpnService sets exactly one DNS server: inner resolver `10.66.x.1` (A's gateway address) — DNS cannot leak outside tunnel because the route for it lives inside the VPN and no other resolver is offered.
- Gateway on A intercepts :53 to its inner address, resolves via A's system resolver (later: optional DoH toggle).
- Inner hostname resolution on A uses `net.Dial("host:port")` through netstack's DialContext → OS resolver.

### IPv4 / IPv6
- B requests BOTH `0.0.0.0/0` and `::/0` routes; inner stack dual-stack.
- A dials the true destination family natively per-flow. If A sits on an IPv6-only carrier (464XLAT), outbound IPv4-literal sockets still work because Android's CLAT operates below the socket layer — **UNKNOWN—NEEDS PROTOTYPE** on such carriers (Phase 0 checklist item).
- ICMP: netstack answers echo internally where possible; traceroute/mtr fidelity is degraded — accepted, documented in Diagnostics.

### UDP behavior
Inner UDP flows get a mapping table on A `{innerSrcIp:port → localUdpSocket}` with 30s idle timeout; replies are reverse-mapped. Consequences: QUIC/HTTP3 works; games/VoIP work with slightly higher jitter; symmetric NAT timeouts apply. Diagnostic escape hatch: "block UDP/443" toggle to force HTTP/2-over-TCP if QUIC misbehaves on some carrier.

### Reconnect logic
WireGuard roaming means whichever side's address changes simply starts sending again; latest authenticated packet resets the peer endpoint. B additionally listens to `ConnectivityManager.NetworkCallback` and refreshes `setUnderlyingNetworks`; expected re-handshake ≤ ~5s after new network validates.

---

## 10. WireGuard Integration Strategy

Two different integrations, deliberately asymmetric:

| | Phone B (client) | Phone A (provider) |
|---|---|---|
| Artifact | Official Maven `com.wireguard.android:tunnel` (Apache-2.0; current line 1.0.2026xxxx) | Custom `hmx-gateway.aar` WE BUILD |
| Inside | Google's packaging of wireguard-go (MIT) + GoBackend managing a VpnService TUN | wireguard-go (MIT) + gVisor netstack (`tun.CreateNetTUN`) + our gateway glue |
| Needs VpnService? | Yes (traffic capture is the product) | **NO** — no TUN, no routes touched on A |
| Why | Captures whole device cleanly, maintained upstream | Official lib on A would write decrypted packets into a TUN the unrooted kernel can't forward/NAT → dead end. Netstack instead hands us **flow-level** access: `tnet.DialContext`/listeners, i.e., a SOCKS-like termination with zero privileges |

This is the exact pattern publicly demonstrated by WireGuard's author (wireguard-go + netstack in-process HTTP client), shipped in production by wireproxy (HTTP/SOCKS through WG, no privileges) and Firestack/Rethink (gomobile netstack + userspace WG on Android). We are recombining known-good parts, not inventing protocol.

**Key handling:** Curve25519 static keypair per device, generated on-device, reused as WG identity AND control-plane identity (public key = device id anchor). Optional per-pairing preshared key (WG PSK) generated by A and delivered to B encrypted to B's public key via control plane. Private keys never leave the device, never logged, never in QR.

---

## 11. Android VpnService Strategy (Phone B)

Manifest: service extends `android.net.VpnService`, `BIND_VPN_SERVICE` permission, `android.net.VpnService` intent-filter (so Always-On works). With the official library we can let GoBackend manage its inner service OR subclass for our lifecycle — decision: subclass/wrap to own notifications.

Builder configuration (per session):
- `addAddress(10.66.x.2/32)` + ULA v6
- `addRoute(0.0.0.0/0)` + `addRoute(::/0)`
- `setDnsServer(10.66.x.1)`, `setMtu(1280)`
- `setBlocking(true)`, `setMetered(false)`
- `protect()` the underlying WG UDP sockets (the official GoBackend already protects them — verified in source)
- `setUnderlyingNetworks(null)` → system picks best transport for tunnel egress

Permission UX: `VpnService.prepare()` before every connect attempt; dedicated explanation screen precedes the system dialog; denial maps to `ErrorVpnDenied` with retry path.

Kill-switch semantics: while CONNECTED, default routes point into the tunnel, so loss of tunnel = no traffic (fail-closed) as long as the VPN interface persists. On explicit disconnect the interface drops and traffic returns to normal. Document honestly: brief reconnect windows can leak if the interface is recreated; offer "Always-On VPN + Block connections without VPN" instructions in Security settings for paranoid mode. Only-one-VPN-per-device is an Android fact: using HMX replaces any other VPN; surfaced in pre-flight checks.

---

## 12. Phone A Gateway Strategy (highest-risk phase — read twice)

**Problem restated:** decrypted packets from B must become real internet traffic from A, without root, without touching A's routes, without a TUN.

**Design — userspace gateway (`hmx-gateway`, Go, gomobile-bound):**

```
WG UDP socket (normal, protected-quality socket on A)
   ↳ wireguard-go device (decrypt → inner IP packets)
       ↳ gVisor netstack virtual NIC (terminates TCP/UDP/DNS properly)
           ↳ gateway glue:
               TCP  → tnet.DialContext(dest) → splice bidirectionally
               UDP  → mapping table → real UDP socket per flow (30s TTL)
               DNS  → :53 to inner addr → resolve via A's resolver → answer
               ICMP → best-effort internal echo
           ↳ accounting hook: byte counters per session (feeds limits + UI)
           ↳ policy hook: hard data limit reached → stop accepting/drop flows
```

Properties that make this correct where naive plans fail:
- No packet spoofing, no raw sockets, no IP forwarding flag — every outbound byte is a legitimate socket owned by the HMX app on A.
- Return traffic is inherently routable because A's sockets get replies naturally; netstack re-encrypts back to B.
- Works identically whether A's uplink is Wi-Fi or cell; follows A's default network automatically (OS-level).

Mobile API surface (gomobile bindings, tiny): `Start(configJSON)`, `Stop()`, `StatsFlow chan/callback`, `SetHardLimit(bytes)`, `LogSink`. All heavier logic stays in Go where the libraries live.

Performance expectations: userspace crypto + netstack on a modern phone plausibly yields tens-to-low-hundreds Mbps; CPU/heat/battery cost nonzero — **UNKNOWN—NEEDS PROTOTYPE** (benchmark gate for Phase 6 exit).

Explicitly rejected alternatives (recorded so nobody retries them):
- Kernel NAT/iptables on A → requires root. Impossible per constraints.
- Official tunnel lib on A + hope kernel forwards → packets die in TUN without ip_forward+MASQUERADE (root). Dead end, proven above.
- Hand-rolled TCP/IP stack in Kotlin → months of edge-case hell; netstack already is that stack, battle-tested.

Upstream-loss behavior on A: gateway detects via per-flow failures + periodic probe (HEAD to generate_204 over A's own network); exposes PROVIDER_DEGRADED to control plane so B can show truthful state instead of silent timeouts.

---

## 13. Direct Connection Strategy

Reachability taxonomy and expectations:

| A's situation | B's situation | Direct likely? |
|---|---|---|
| Public IP / router port-forward / UPnP succeeded | anything | YES (best case) |
| Home Wi-Fi behind cone/full-cone NAT | mobile data | OFTEN (punch usually succeeds) |
| Carrier CGNAT (endpoint-dependent mapping) | mobile data | UNRELIABLE — frequently fails |
| Either side blocks inbound UDP | — | NO |

Procedure:
1. Both sides register observed endpoints (local candidates + STUN-derived public mapping) to control plane under the session.
2. Simultaneous-open punch: both fire periodic keepalives at each other's candidate lists for ~15s. WireGuard's roaming accepts whichever lands.
3. If handshake achieved directly → mode=DIRECT, done.
4. Else → RELAY fallback (§14). Never route via relay when direct works; re-probe direct opportunistically every N minutes and upgrade if it starts working.

STUN: lightweight — a handful of public STUN servers via a tiny UDP client (no dependency needed beyond a socket; RFC5389 messages are ~50 lines). UPnP/PCP on A's home router: optional Phase 8 stretch; SSDP+SOAP hand-rolled, marked UNKNOWN—NEEDS PROTOTYPE, skippable.

Honesty clause: mobile-carrier↔mobile-carrier direct success is expected to be minority-case. The product must feel great anyway → fast automatic relay fallback with clear UI labeling ("via relay").

---

## 14. Relay Strategy

When direct fails (expected common case), fall back:

```
B ──WG──► VPS hub ──WG──► A ──► internet
        (hub-and-spoke WireGuard, one interface, two peers;
         VPS ip_forward enabled — rooted Linux box, trivial there)
```

- A maintains a persistent outbound-only WG tunnel TO the relay (keepalive) → traverses A's NAT forever, no inbound need. Same for B.
- Relay config: single WG interface; peer-B AllowedIPs=inner-B/32, peer-A AllowedIPs=inner-A/32; enable forwarding between them. ~20 lines of config on a rooted box. No custom software needed on the VPS.
- Selection: control plane advertises relay endpoint; both peers add it as second allowed-peer path; traffic switches by WG route priority. Health check: RTT probe via relay every 30s; if relay unreachable → ErrorRelayUnavailable with retry/backoff.
- Costs: smallest viable VPS (~$4–6/mo) suffices for personal use; bandwidth is doubled through it (B→VPS→A); latency += RTT(B,VPS)+RTT(VPS,A) — typically +20–80ms regional.
- Privacy stance: relay operator (= owner, it's their VPS) sees ciphertext, inner-IP headers, and SNI-level metadata; TLS content stays opaque. Documented, accepted for personal use.
- Note: free-tier Supabase pausing affects only CONTROL plane (new pairings/signaling), never established tunnels — endpoints are cached. Acceptable; migrate control plane onto the relay VPS in Phase 8 if pauses annoy.

---

## 15. Backend / Control Plane

**Choice: managed Supabase** (Postgres + Realtime websockets + Edge Functions) — zero ops, generous free tier, realtime signaling included; swappable later because all backend code hides behind the `control` repository layer. Self-host alternative (FastAPI+SQLite on the relay VPS) documented as migration path, not built first.

Tables (minimal):
- `devices(id, pubkey, name, role_capable, created_at, last_seen_at, revoked_at)`
- `pairings(code_hash, provider_device, user_device?, created_at, expires_at, status[pending|claimed|expired|rejected], attempts)`
- `sessions(id, provider_dev, user_dev, started_at, ended_at, bytes_up, bytes_down, mode[direct|relay], end_reason)`
- `signals(channel, payload, ts)` — ephemeral Realtime channel messages (endpoints, accept/reject), not persisted or purged fast.

Auth model: no emails/passwords ever. Each device authenticates by signing a challenge with its Curve25519-derived key (Edge Function verifies). Authorization: B may act only on pairings granted by A; A may revoke anytime; revocation propagates over the signal channel and is enforced at next handshake (keys removed from A's gateway allowlist).

Backend deliberately does NOT know: location, contacts, browsing, IPs of destinations, plaintext anything, private keys. It knows: public keys, display names, pairing lifecycles, coarse session metadata. Retention: signals purged ≤24h; sessions retained locally on devices, backend keeps only what's needed for cross-device history sync (optional, off by default).

---

## 16. Pairing Architecture

Code spec: **8 chars, Crockford base32 (no 0/O/1/I/L)** ≈ 40 bits entropy; TTL **5 minutes**; single-use; server-side stored as SHA-256 hash; max 5 verification attempts per code then dead; regeneration allowed.

Sequence:
1. A taps Start Sharing → generates code + opens Realtime channel keyed by `code_hash`.
2. QR payload contains ONLY: `{v:1, ctl_url, code}` — no keys, no tokens, no endpoints (nothing sensitive in QR; a stolen photo of the QR dies in ≤5 min and yields nothing but a pairing attempt that A must approve).
3. B scans → presents `{code, B_pubkey, B_name}` over the channel → A shows confirm card (name + key-fingerprint prefix) → A approves.
4. Server/Edge mints ephemeral session credentials; A generates WG PSK, encrypts to B_pubkey, posts; both derive identical tunnel config (inner IPs, port, MTU, endpoints).
5. Pairing transitions claimed→consumed; code invalid afterward. Revocable from A's device list at any time.

Threat notes: brute-force 40-bit space throttled by attempt-counter + rate limits; MITM prevented by key fingerprints shown on BOTH screens at approval time (trust-on-first-use recorded in device registry thereafter).

---

## 17. Security Threat Model

| Threat | Defense |
|---|---|
| Malicious pairing attempts / code brute-force | 40-bit code, TTL 5min, single-use, 5-attempt lockout, rate-limited endpoints, explicit human approval on A |
| Stolen pairing code (shoulder-surf/photo) | Expires fast; requires live approval interaction on A; grants nothing post-consumption |
| Stolen QR | Same as above; QR carries zero long-lived secrets |
| Compromised/lost device B | One-tap revoke in A's registry; revocation checked at handshake + via signal push; session keys rotated per pairing |
| Private-key exposure | Generated in app, wrapped by Android Keystore AES-GCM master key, stored encrypted (envelope blob in DataStore). Never logged/displayed/exported; only public fingerprint shown |
| MITM on control channel | TLS (platform) + payload signatures with device keys + fingerprint confirmation at first pairing |
| Fake provider / fake user device | Identity = pubkey pinned in registry post-first-pairing; unknown keys rejected; approval card shows fingerprint prefix |
| Backend compromise | Server never holds private keys or PSKs in plaintext (PSK delivered E2E-encrypted); worst case = metadata exposure + DoS; rotation procedure documented |
| Relay compromise | Sees ciphertext + inner headers only; content TLS-protected end-to-end by origin apps; mitigate by owning the relay |
| Traffic leakage around tunnel | Default-route capture on B; protected WG socket prevents loops; Security screen documents Always-On + block-without-VPN hardening |
| DNS leakage | Single inner resolver inside tunnel; no secondary DNS offered; Diagnostics includes leak test |
| Accidental internet exposure of A | Gateway binds nothing to public interfaces besides ephemeral WG UDP; no open management ports; LAN interfaces not listened on for control |
| Sensitive logging | Central logger redacts keys/IPs/QR payloads at WARN-and-below; verbose logs opt-in, local-only, excluded from exports unless toggled |

---

## 18. Data Privacy Plan

Collected (minimum viable): device public key, chosen device name, pairing lifecycle events, session metadata (times, bytes, mode). Needed for: authentication, pairing, abuse-of-my-own-tunnel prevention, diagnostics.

Never collected/transmitted: phone number, contacts, location (network-type label like "Wi-Fi/Cellular" is not GPS and is fine), browsing history, destination addresses, app inventory, advertising IDs.

Local-only by default: full session history, logs, diagnostics. Backend mirrors only what §15 lists. No analytics SDKs. Crash reporting: none in v1 (personal sideload; local tombstone screen offers "share report" manually).

---

## 19. Data Model

Pure-Kotlin domain models (fields only; implementation comes in Phase 1):

| Model | Fields |
|---|---|
| Device | id(=pubkey-derived), name, role, createdAt, lastSeenAt, status(online/offline/revoked), networkType(wifi/cell/unknown) |
| Pairing | pairingId, codeHash, providerDeviceId, userDeviceId?, createdAt, expiresAt, status(pending/claimed/rejected/expired/consumed), attemptsLeft |
| Session | sessionId, providerId, userId, startedAt, endedAt?, bytesUp, bytesDown, mode(direct/relay), endReason(user/provider/network/limit/error) |
| Settings | deviceName, rolePreference, autoConnect(bool), dailyLimitBytes(Long?), warningThresholdPct(Int), hardLimitEnabled(bool), blockQuic443(bool), verboseLogs(bool) |
| TunnelConfig (internal, secret-bearing) | private key ref, peer pubkey, psk ref, endpoints, inner addresses, mtu, keepalive |

Storage strategy: Room for queryable history (sessions, devices); DataStore for Settings; envelope-encrypted DataStore blob for secret material (Keystore AES-GCM master key wraps a random data key). Sensitive fields NEVER in Room.

---

## 20. State Machine

Two independent machines — provider-side and user-side — because they run on different phones and have different vocabularies. Modeled as sealed class hierarchies, single source of truth in repositories, exposed as `StateFlow<ProviderState>` / `StateFlow<ClientState>`, consumed in Compose via `collectAsStateWithLifecycle`. Transitions happen through explicit event functions; illegal transitions are compile-time-discouraged (exhaustive `when`) and runtime-logged.

**ProviderMachine:** `IDLE → PREPARING(uplink check, limits ack) → SHARING_ADVERTISING(code live) → PEER_AUTHENTICATING(approval pending) → SHARING_CONNECTED ↔ PEER_ROAMING → DISCONNECTING → IDLE`; terminal-error branch `ERROR(reason) → IDLE`.

**ClientMachine:** `IDLE → PAIRING_SCANNING → DEVICE_FOUND → AUTHENTICATING → VPN_PERMISSION_REQUIRED → STARTING_TUNNEL(handshake) → PROBING(DNS+204 check) → CONNECTED ↔ RECONNECTING(network change; sub-states show cause) → DISCONNECTING → IDLE`; error branch `FAILED(reason) → IDLE` with reason enum matching §21.

Rules: no boolean shadows of these states anywhere; transient UI concerns (button ripple etc.) are local composable state, not machine state.

---

## 21. Error State Architecture

Every failure maps to: machine-state + typed reason → dedicated full-screen composable (not toast) with: what happened, what it means for connectivity, primary action (retry/reconfigure), secondary action (diagnostics/help).

| Reason | Trigger detection | Primary action |
|---|---|---|
| NO_INTERNET (self) | Connectivity check before flows | Open network settings |
| PROVIDER_OFFLINE | Signaling unreachable / handshake dead >45s | Retry · notify provider |
| PAIRING_EXPIRED | TTL elapsed | Regenerate/rescan |
| PAIRING_REJECTED | A declines | Back to scan |
| VPN_PERMISSION_DENIED | prepare() result/dialog deny | Re-request · explain |
| HANDSHAKE_FAILED | no handshake within 15s ×3 attempts | Switch to relay suggestion |
| TUNNEL_FAILED | wg turnOn error / establish null | Restart tunnel |
| PROBE_FAILED (connected-but-dark) | handshake OK, HTTP204/DNS fail via tunnel ×3 | Tells truth: "linked but provider has no internet" |
| DNS_FAILURE | probe isolates resolution step | Toggle DoH hint |
| RELAY_UNAVAILABLE | relay probes fail | Retry · check VPS |
| TIMEOUT | generic op deadline | Retry |
| PROVIDER_STOPPED | signal/teardown from A | Acknowledge |
| DISCONNECTED_BY_PEER | revocation or A stop mid-session | Acknowledge |
| NETWORK_CHANGED | callback fired | Auto-reconnect banner (transient) |
| SERVICE_STOPPED | FGS killed / watchdog detects death | Auto-restart attempt + banner |
| CRASH_RECOVERED | cold start finds stale desired-state | "We restored your session" banner |

All reasons carry a stable code for logs/diagnostics export.

---

## 22. Battery / Background Strategy

- **Phone A:** `GatewayService` = foreground service, type `specialUse` (fallback `dataSync`), permanent low-priority notification with live counter. Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (acceptable for sideloaded personal app; Play-policy caveats irrelevant here) + OEM-specific "don't kill me" deep links (MIUI/EMUI/Samsung) listed in Settings→Security with per-vendor instructions.
- **Phone B:** the VpnService itself is system-managed; while active-VPN the app qualifies for `systemExempted` FGS type (verified: Android docs allow it for active VPN apps). Notification shows state pill.
- Screen-off: WG keepalives + FGS keep both alive; Doze restricts alarms not active sockets — sustained flows continue. Watchdog: lightweight periodic WorkManager check reconciles DESIRED state (persisted in DataStore) vs ACTUAL (service alive? handshake fresh?) and repairs drift.
- Process killed by OS/OEM: desired-state persistence means next launch (or boot receiver, opt-in) restores: A resumes advertising/sharing; B reconnects to last provider. Recovery banner per §21.
- Honest expectation-setting: aggressive OEM killers remain the #1 support issue class; mitigations reduce but never eliminate.

---

## 23. Network Switching Strategy

| Scenario | Behavior | Expected downtime |
|---|---|---|
| A: Wi-Fi→5G | A's sockets follow OS default network; gateway unaffected (it dials via OS); WG endpoint for B changes → roaming handles | ~1–3s blip |
| A: 5G→Wi-Fi | same | ~1–3s |
| B: Wi-Fi→cell | B's NetworkCallback → setUnderlyingNetworks + keepalive re-fire; WG re-handshakes to A's endpoint | ~3–8s |
| B: cell→Wi-Fi | same | ~3–8s |
| Both briefly offline | keepalives fail silently; WG tolerates; flows stall; both resume when any connectivity returns; PROBE revalidates | until restored |
| A loses internet | A probes own uplink → publishes DEGRADED; B shows PROBE_FAILED truth-state instead of hanging apps | n/a (outage) |
| B loses transport | B shows RECONNECTING with cause | until restored |

Principle: never fake connectivity. States reflect reality; UI communicates rather than hides.

---

## 24. Technology Selection

| Component | Candidate(s) | Recommendation | Reason |
|---|---|---|---|
| UI | Jetpack Compose (+M3) | **Compose, XML forbidden** (per spec) | mandated; modern; testable |
| Language | Kotlin / Java | **Kotlin 2.x** | coroutines/Flow native |
| Min SDK | 26 / 29 / 31 | **26 (Oreo)**, target latest | VpnService+FGS baseline; wider device pool; API-34 FGS types handled conditionally |
| VPN | Android VpnService | **Yes, via official GoBackend on B** | only sanctioned whole-device capture |
| Tunnel | WireGuard (custom proto rejected) | **wireguard-go lineage** | mandated; audited; roaming+crypto solid |
| Client WG lib | com.wireguard.android:tunnel | **Yes** (Apache-2.0, Maven Central, maintained) | removes Go-build burden for B side |
| Provider gateway | Kotlin userspace stack / root NAT / **Go netstack AAR** | **hmx-gateway: wireguard-go+gVisor netstack via gomobile** | only unrooted-correct path with proven precedent (wireproxy/Firestack) |
| Backend | FastAPI / Firebase / **Supabase** | **Supabase** (managed PG+Realtime) | zero-ops signaling+registry today; clean swap path to FastAPI-on-relay-VPS tomorrow |
| DB (backend) | Postgres (via Supabase) | **Postgres + RLS** | relational fit; row-level security for device-scoped data |
| DB (local) | Room / SQLDelight | **Room** | first-party, coroutine-native, agent-familiar |
| KV/prefs | SharedPreferences / DataStore | **DataStore (proto/preferences)** | async, coroutine-first |
| Secure storage | Android Keystore | **Keystore AES-GCM envelope** around secret blob | hardware-backed master key; X25519 material itself stays software (Keystore lacks WG curve) |
| QR scan | ZXing embed / **ML Kit code scanner** | **ML Kit (Play Services)**, ZXing fallback | no camera perm needed via play-services model; reliable |
| QR render | ZXing core | **ZXing core (generate only)** | tiny, offline |
| Networking (app) | Retrofit+OkHttp / Ktor | **OkHttp + Retrofit + kotlinx.serialization** | boring, documented, stable |
| STUN | TURN-heavy libs / hand-roll | **Hand-rolled minimal RFC5389 UDP client** | binding queries only; ~small; no dep |
| DI | Hilt / manual | **Hilt (KSP)** | convention beats cleverness for agent-implemented code |
| Relay host | VPS | **Cheapest KVM VPS ($4–6/mo), plain WireGuard + ip_forward** | rooted Linux = 10-line config; no custom relay software |

Rejected-for-now: Tor/obfuscation layers, QUIC transport for WG, multi-hop chains, WebRTC-datachannel transport (all YAGNI for v1; noted in §32).

---

## 25. Project Structure

Evaluated the prompt's draft tree: direction right, but premature multi-module split (domain/data/app) adds Gradle overhead with zero payoff at this size — especially under a mobile/constrained dev workflow. Verdict: **ONE Android module + ONE native-artifact module**, packages inside :app mirror layers.

```
HMX-HOTSPOT/
├── app/                          # the Android application (single module)
│   └── src/main/kotlin/hmx/
│       ├── ui/                   # screens by feature (compose only)
│       │   ├── onboarding/ home/ provider/ user/ devices/ activity/
│       │   ├── settings/ diagnostics/ error/
│       │   └── theme/ components/
│       ├── navigation/
│       ├── domain/model/         # pure Kotlin models (Device, Pairing, Session…)
│       ├── domain/logic/         # state machines, limit calculator, probes
│       ├── data/local/           # Room DAOs, DataStore
│       ├── data/control/         # Supabase client, signaling, pairing repo
│       ├── tunnel/               # WRAPS com.wireguard.android:tunnel (client side)
│       ├── gateway/              # WRAPS hmx-gateway.aar (provider side) + GatewayService
│       ├── vpn/                  # HmxVpnService, builder assembly, dns config
│       ├── security/             # keys, envelope storage, pairing crypto
│       └── core/                 # logging(redacting), error taxonomy, dispatchers
├── gateway-native/               # GO module: wireguard-go + netstack + gateway glue
│   └── (built in CI via gomobile → hmx-gateway.aar, committed as release artifact
│        or fetched via Gradle artifact dependency — NOT rebuilt on every PR)
├── docs/                         # README/ARCHITECTURE/NETWORKING/SECURITY/PAIRING/VPN/
│                                 # DEVELOPMENT/TESTING/TROUBLESHOOTING/ROADMAP (§25-docs)
├── .github/workflows/            # ci.yml, release.yml (§27)
└── gradle/libs.versions.toml     # version catalog
```

Why the odd-looking pieces exist: `gateway-native` is separate because its toolchain (Go+NDK) differs from the JVM build and should build rarely; everything else stays one module so the project compiles fast on constrained hardware/cloud CI.

Planned documentation set (content owners): README (what/quickstart/screenshots) · ARCHITECTURE (this doc distilled) · NETWORKING (modes, addressing, switching) · SECURITY (threat model §17) · PAIRING (protocol §16) · VPN (B-side internals §11) · DEVELOPMENT (build, CI, gateway artifact workflow) · TESTING (matrix §26) · TROUBLESHOOTING (OEM killers, carrier quirks, QUIC) · ROADMAP (phases §28 + future §32).

---

## 26. Testing Strategy

Instrumentation reality: meaningful VPN tests need TWO devices (or device+emulator with host-side WG peer standing in for A). Unit tests cover machines/crypto/config-building; instrumented matrix covers the rest.

| Category | Cases (condensed) | Type |
|---|---|---|
| Basic | launch, onboarding paths, role pick, pairing create/scan/type, QR expiry | unit+instrumented |
| VPN | permission grant/deny, start/stop, reconnect, network switch mid-flow, screen-off soak, reboot restore, Always-On interplay | instrumented (2 devices) |
| Networking | DNS resolve via tunnel (leak-checked), IPv4 site, IPv6 site, TCP bulk download, UDP (QUIC) site, HTTPS through tunnel, streaming 10 min, large-file integrity (checksum) | instrumented |
| Apps | Chrome browse, YouTube 3-min playback, Play Store small update, Instagram feed+image, WhatsApp message+photo both ways, Maps tiles | manual scripted |
| Failure | wrong code, expired code, revoked device reuse, provider offline pre/mid-session, VPN denied, tunnel crash, relay down, process kill (adb + OEM), malformed pairing payloads (fuzzed) | instrumented+manual |
| Security | unauthorized pubkey handshake attempt, replayed pairing claim, expired-code claim, key-export absence check, log-redaction audit | unit+manual |
| Gateway unit | netstack loopback harness: fake peer dials through gateway to local echo server (TCP/UDP/DNS); byte-accounting accuracy ±0; hard-limit cuts at threshold | Go tests in gateway-native |

Exit rule per phase: its matrix slice green + no regression in prior slices. MVP gate = §31 acceptance list.

---

## 27. GitHub Actions Strategy

Workflows (no local Android Studio required):

| Job | Steps (conceptual) | Trigger |
|---|---|---|
| `lint-test` | setup JDK 17 temurin · gradle cache · ktlint · detekt · `testDebugUnitTest` | PR/push |
| `build-debug` | `assembleDebug` · upload APK artifact | PR/push |
| `build-release` | version from tag · signed with secrets-backed keystore · `assembleRelease` + mapping upload | tag vX.Y.Z |
| `gateway-native` | setup-go · NDK · gomobile bind → hmx-gateway.aar · upload artifact + attach to release; cache Go modules | changes under gateway-native/** or manual |
| `deps-verify` | `gradle --dependency-verification strict` checksum lockfile check | weekly + PR |

Notes: dependency verification lockfiles committed; gradle wrapper only; concurrency-cancel superseded runs; artifact retention 14d for debug builds; signing key lives in GH secrets (base64 keystore), never in repo. Total PR wall-time target <12min (gateway job cached, runs only when touched).

---

## 28. Development Phases

| Phase | Goal | Exit criteria (gate) | Risk |
|---|---|---|---|
| **0 Research/Prototypes** | Kill uncertainty BEFORE scaffolding | P0.1: netstack PoC — Go program: WG peer + netstack serves TCP through it on desktop AND gomobile-built demo dials google.com through a real remote WG peer. P0.2: measure direct-punch success on 3–5 real network pairs. P0.3: 1h battery soak of bare WG listener on a phone. All results written into docs/NETWORKING.md | highest value, mandatory |
| **1 Foundation** | Skeleton compiles in CI | Compose app, nav shell, theme, Hilt, Room+DataStore, redacting logger, CI green | low |
| **2 UI/UX (mock)** | Every screen navigable on fake state machines | all §5 screens + error states reachable via debug drawer; screenshot smoke test | low |
| **3 Pairing/Control** | Real registration+pairing vs Supabase | A↔B exchange code→approval→shared secrets E2E; revocation propagates; fuzzed payloads rejected | medium |
| **4 WireGuard tunnel (transport only)** | Handshake between devices using paired keys | handshake <3s LAN; stats visible; roaming across B networks works; NO internet sharing claimed yet | medium |
| **5 Android VPN (B side)** | Whole-device capture into tunnel | B's traffic visibly transits tunnel (tcpdump on A side of WG); VPN perm UX done; reconnect matrix green | medium |
| **6 Provider gateway** | Userspace gateway serves flows | Go-loopback suite green (§26); real phone A serves TCP/UDP/DNS to B through gateway; benchmark numbers recorded | **highest** |
| **7 Real internet** | Product works end-to-end | §31 acceptance list passes | high |
| **8 Direct/Relay** | Automatic mode selection | punch procedure implemented w/ P0.2-informed heuristics; relay hub deployed; failover <15s; health checks | high |
| **9 Security review** | Threat-model pass | §17 checklist audited against code; log-redaction tests; secret-scan clean | medium |
| **10 Production QA** | Device farm of convenience | matrix §26 executed on ≥3 devices (one aggressive-OEM), Android 26→latest span | medium |

Rule: no phase starts before previous gate passes; Phase 0 findings may legitimately reshape §12/§13 — that's the point.

---

## 29. Risks and Technical Unknowns

| # | Risk / Unknown | Severity | Mitigation |
|---|---|---|---|
| R1 | Gateway throughput/CPU/heat on mid-range phones | HIGH | Phase 0.3 + Phase 6 benchmarks; if poor: ship anyway with honest speed expectations, optimize later (batch crypto already in wg-go) |
| R2 | Direct-P2P success rate on double-CGNAT | HIGH | assume-relay design; punch is opportunistic upgrade, never a blocker |
| R3 | Relay cost/ops (bandwidth billed both directions) | MED | personal scale = few GB/day worst case; cheapest VPS; document expected bill |
| R4 | OEM background killers (esp. MIUI) | MED-HIGH | §22 playbook; watchdog; set expectations in-app |
| R5 | IPv4-literal dialing on IPv6-only carriers (CLAT assumptions) | MED | Phase 0 checklist; fallback: gateway-side DNS64/NAT64 awareness — UNKNOWN—NEEDS PROTOTYPE |
| R6 | QUIC/UDP quirks per carrier | LOW-MED | diagnostic block-UDP443 toggle |
| R7 | gomobile/NDK toolchain flakiness in CI | MED | gateway artifact cached/released rarely; JVM build never blocked by Go job |
| R8 | Supabase free-tier pauses break NEW pairings (not live tunnels) | LOW | cached endpoints resume direct/relay; migrate control plane to VPS if annoying |
| R9 | Play Store policies (if ever published): FGS use, battery-exemption permission | LOW for v1 | sideload/APK distribution declared for v1; revisit if publishing |
| R10 | App-compat edge cases (apps pinning certs, excluding VPN) | LOW | inherent Android limitation, documented |

---

## 30. Feasibility Assessment (explicit answers)

**Q1 — Can unrooted Phone A realistically act as gateway for B's traffic with this architecture?**
YES — but only as a *userspace* gateway. It cannot be a kernel router/NAT. Every inner flow must terminate on A and be re-originated as A's own socket. That is precisely the netstack design in §12, and it has public precedent (Donenfeld's netstack demo, wireproxy, Firestack). Mark: feasible, prototype-gated on performance (R1).

**Q2 — What Android APIs are available?**
`VpnService`(prepare/Builder/establish/protect/setUnderlyingNetworks/always-on), foreground services + types (incl. `systemExempted` for active VPN apps), `ConnectivityManager` network callbacks, Android Keystore, `WorkManager`, ML Kit barcode, `ParcelFileDescriptor`. Verified against current developer docs.

**Q3 — What restrictions exist?**
No root networking primitives (iptables/ip_forward/raw route manipulation). One active VPN per device/user. FGS types mandatory (API 34+) with runtime prerequisites. Background-start restrictions; OEM-specific killers. Battery-optimization exemption is user-granted. VPN apps can't capture traffic of apps that exclude themselves or other-profile contexts.

**Q4 — Can this work over mobile carrier networks?**
YES for the tunnel itself (outbound UDP works; roaming handles mobility). BUT inbound-direct to a CGNAT'd carrier phone generally FAILS — hence relay fallback (§13/§14). Data-plane performance subject to R1.

**Q5 — When is a VPS/relay required?**
Whenever simultaneous hole-punch fails: typically double-CGNAT pairs, symmetric NATs, or UDP-hostile networks. Expected frequency: majority case for cellular↔cellular; minority for home-Wi-Fi providers. Design assumes relay availability from day one.

**Q6 — Can Phone A forward/NAT without root?**
Kernel-space: NO (definitively). Userspace flow-proxy: YES (this plan's §12). There is no middle ground.

**Q7 — What parts need native code?**
The data plane crypto/routing already ships as prebuilt `.so` inside the official tunnel lib (B side needs zero native building). Provider side needs OUR Go module compiled via gomobile (Go + NDK in CI only). Everything else is pure Kotlin/JVM.

**Q8 — Can the official WireGuard Android tunnel library be embedded directly?**
YES — `com.wireguard.android:tunnel` on Maven Central (Apache-2.0), actively maintained; source review confirms GoBackend manages TUN establishment, socket protection, MTU/kill-switch semantics. Used as-is on Phone B behind our thin wrapper.

**Q9 — What must be implemented in Kotlin?**
UI/Compose, state machines, pairing/control-plane client+crypto glue, key/envelope storage, VpnService assembly (B), GatewayService + stats/limits orchestration (A), watchdogs, diagnostics. In Go (gateway-native): WG+netstack wiring, TCP/UDP/DNS forwarding glue, accounting hooks.

**Q10 — What cannot reliably be implemented on standard unrooted Android?**
Kernel tethering/NAT; guaranteed direct P2P through CGNAT; capturing traffic of VPN-excluding apps/work profiles; perfect always-alive background under hostile OEMs; sub-second failover guarantees. All acknowledged and designed around, none fatal to the product.

**Bottom line:** the original idea IS achievable on stock Android without root, provided the provider side is built as a userspace gateway and the product honestly embraces relay fallback. No silent hand-waving anywhere in this plan.

---

## 31. MVP Definition

**In scope:** onboarding + role select; QR+code pairing via Supabase; provider sharing with foreground service, live counters, revoke, hard/soft data limits (provider-enforced); user full-device tunnel with VPN perm UX, auto direct→relay, reconnect handling; session history; diagnostics screen; the §21 error states; dark-first polished UI.

**Out of scope (explicitly):** multi-hop, split tunneling per app, UPnP automation, DoH toggle, light theme polish, cloud history sync, auto-start on boot (stretch), iOS, anything monetized.

**Objective acceptance tests (ALL must pass; handshake alone is NOT success):**
1. Chrome loads arbitrary sites on B with A on cellular; exit-IP check shows A's carrier IP.
2. YouTube: 3-minute video streams on B, seek works, quality ≥480p on decent uplink.
3. Play Store installs/updates a small app on B through tunnel.
4. WhatsApp: text + photo delivered both directions on B.
5. Maps tiles render on B.
6. DNS-leak test: resolvers observed = A's path only; no B-carrier DNS seen.
7. Large-download integrity: ≥100MB file, SHA matches source (proves stable TCP through gateway).
8. Stability: 10-min continuous session, zero manual intervention; screen-off 30-min survival on both phones.
9. Resilience: toggle B airplane mode → auto-recovers ≤15s; toggle A Wi-Fi↔cell → ≤15s blip.
10. Limits: hard limit fires within ~5% of configured value; sharing halts; warning toast at threshold.
11. Revoked device cannot reconnect; live session tears down ≤30s after revoke.
12. Cold-kill both apps (adb + OEM cleaner) → relaunch restores intended states.

---

## 32. Future Features (post-MVP backlog, unprioritized)

Split tunneling (per-app include/exclude on B) · Wi-Fi-only sharing rule on A · speed-test screen · DoH/DoQ on gateway · auto-boot resume · multi-provider for one user · LAN-access toggle (let B reach A's LAN) · obfuscation transport (udp2raw-style) for hostile networks · UPnP/PCP automation on A · self-hosted control plane on relay VPS · usage reports/quotas per paired device · light theme completion · tablet layouts.

---

## 33. Final Recommended Architecture (one-glance summary)

```
Phone B                                    Phone A
┌─────────────────────────┐               ┌──────────────────────────────┐
│ Compose UI (dark, M3)   │               │ Compose UI (dark, M3)        │
│ ClientStateMachine      │               │ ProviderStateMachine         │
│ HmxVpnService           │               │ GatewayService (FGS)         │
│  └ official wg tunnel   │   WireGuard   │  └ hmx-gateway.aar           │
│    lib (Apache-2.0)     │◄═ UDP direct ═│     wireguard-go + gVisor    │
│ protect(), ::/0+0/0/0   │  or via VPS   │     netstack → real sockets  │
│ DNS=10.66.x.1 (inner)   │               │ DNS fwd · counters · caps    │
└───────────┬─────────────┘               └──────────────┬───────────────┘
            │      control plane: Supabase PG+Realtime   │
            └────── pairing · signaling · registry ──────┘
                    (never carries user traffic)
```

Decisions locked: Kotlin/Compose/Hilt/Room/DataStore · official WG lib on B · custom netstack AAR on A · Supabase control plane with FastAPI escape hatch · direct-first with honest relay fallback · provider-enforced limits · two sealed state machines · single Gradle app module + separate native artifact · GitHub Actions as the only required build environment.

**Gate:** implementation begins with Phase 0 prototypes only after this document is reviewed and approved; Phase 0 findings amend §12/§13 before Phase 1 scaffolding.

# Phase 6 — NAT Traversal, Direct Connection & Relay Fallback

## Architecture
WireGuard stays the encrypted data plane. Phase 6 only answers "how do the two
phones reach each other":

```
Supabase (candidates, relay tokens) — signaling only
Provider: STUN(SRFLX)+HOST candidates → published to peers.candidates
Consumer: tries endpoints in order HOST → SRFLX → stored endpoint (10 s handshake+probe each)
          all fail → hmx_allocate_relay → token → UDP relay forwards encrypted packets
```

## Components
- `gateway-native/cmd/relay` — Go UDP forwarder. Forwards opaque datagrams
  consumer↔provider; never decrypts; no DB. Safeguards: token registration,
  max 256 sessions, 60 s idle reaper, 1500-byte packet cap, adopt-once
  consumer binding.
- `hmx.net.StunClient` — minimal RFC 5389 binding request / XOR-MAPPED-ADDRESS.
- `hmx.net.StunDefaults` — configurable server list (Google/Cloudflare).
- `hmx.net.NetworkCandidate` + `CandidateSelector` — typed candidates with
  expiry + validation; ordering HOST < SERVER_REFLEXIVE < RELAY.
- RPCs: `hmx_set_candidates`, `hmx_allocate_relay` (10-min single-use tokens in
  `relay_sessions`), candidates returned by `hmx_request_status`.

## Timeouts
Direct attempt 10 s/candidate · relay path 15 s · STUN 4 s · relay session TTL
10 min (control plane) / 60 s idle (data plane).

## Direct→Relay switching
One-way at connect time. No live migration (reliability over cleverness);
relay persists until session restart.

## Known limitations
- Relay address is deployment config (`TraversalConfig.relayAddress`) — no
  discovery protocol yet.
- Consumer binding on relay is adopt-once; a second consumer with the token
  cannot steal an active session but could pre-empt allocation.
- NAT classification is implicit (attempt outcomes), not measured.
- Real multi-device NAT traversal NOT YET AVAILABLE (single device).

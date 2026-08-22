# SUPABASE_RESET_REPORT.md — HMX Remote Internet

**Date:** 2026-08-22 · **Project:** `Hmx notes app` (ref `qemhnhxlxhnyufmjybgj`, ap-south-1) — the only project in the connected org; name contains HMX; database contains ONLY old HMX pairing experiments (zero notes-app data despite the project label). Confirmed intended target per destructive-reset checklist.

## Inventory BEFORE reset

**public schema — 12 tables (all RLS-enabled), all old experimental HMX:**

| Table | Rows | Purpose in old impl |
|---|---|---|
| profiles | 2 | display names for 2 Supabase-auth users |
| external_accounts | 2 | GitHub accounts linked in a browser-pairing experiment (`encrypted_credentials` column existed — values NOT exported) |
| external_authorizations | 4 | OAuth artifacts incl. scopes/expiry |
| oauth_states | 4 | OAuth PKCE state hashes |
| devices | 5 | browser/mobile "devices" of old flow |
| device_pairing_requests | 5 | old pairing requests (PENDING/APPROVED) |
| temporary_sessions | 3 | time-boxed sessions w/ approve-by-device |
| session_accounts / session_permissions | 3 / 8 | per-session grants |
| audit_logs | 24 | activity trail |
| user_roles | 0 | app_role enum(user,admin) |
| rate_limit_events | 218 | fixed-window counters |

**Functions (security definer):** handle_new_user, has_role(app_role), hmx_approve_pairing(...uuid[],text[],int,text), hmx_rate_limit, hmx_touch_updated_at, rls_auto_enable.
**Triggers:** t_ea_updated, t_eauth_updated, t_profiles_updated (updated_at).
**Enums:** app_role(user,admin). **Views:** none. **Edge Functions:** none. **Storage buckets:** none. **Realtime publications:** stock only.

**auth schema:** stock GoTrue; 2 legacy users (+identities/sessions/refresh_tokens rows) from the experiments.

## Backup snapshot (auditable, secrets excluded)

Exported JSON summary (hashes/tokens/credentials omitted by design):
- profiles: `hmx` (dac8b6f3-…), `harsh` (f76d8a54-…), created 2026-08-15.
- devices: 5 × "Android Phone" (ACTIVE/PENDING/EXPIRED mix), 2026-08-15..16.
- device_pairing_requests: 5 (3 APPROVED, 2 PENDING), TTLs ≈ 5–7 min.
- temporary_sessions: 3 EXPIRED (30–60 min TTLs; one revoked after ~50 s).
- audit_logs: 24 events (DEVICE_PAIRING_CODE_CREATED/STARTED/APPROVED, SESSION_CREATED/ENDED, GITHUB connect/disconnect).
- external_accounts(redacted): 2 × github `bharti1233` CONNECTED (encrypted_credentials NOT exported).
- rate_limit_events: counters incl. pair:enter/poll/approve/end buckets from IPs (203.192.239.x, 115.98.235.x, …).

## Removal executed

Single transaction:
1. DROP triggers t_ea_updated / t_eauth_updated / t_profiles_updated.
2. DROP functions handle_new_user, has_role, hmx_approve_pairing, hmx_rate_limit, hmx_touch_updated_at, rls_auto_enable (+ their privileges).
3. DROP TABLE public.{session_permissions, session_accounts, oauth_states, external_authorizations, external_accounts, temporary_sessions, device_pairing_requests, audit_logs, rate_limit_events, user_roles, profiles, devices} CASCADE.
4. DROP TYPE public.app_role.
5. DELETE FROM auth.users (removes the 2 experiment users; cascades identities/sessions/refresh_tokens).
6. REVOKE ALL on schema public from anon/authenticated (re-granted narrowly by the new migration).

Nothing outside this scope was touched (storage/realtime/extensions untouched).

## Fresh start reference

New control-plane design lives in migration `20260822_hmx3a_control_plane` and Edge Function `hmx-auth`; see PHASE_3A_REPORT.md.

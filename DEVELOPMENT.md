# DEVELOPMENT.md

## Environment constraints (read first)

1. **No local Android builds.** This project is developed from a constrained device; the Android SDK is not installed and APK builds are explicitly out of scope locally. All Android compilation happens in GitHub Actions.
2. **The repo lives on an sdcard mount without file locking** (`/mnt/sdcard`, exFAT/FUSE). Go and Gradle cannot run there (`flock: function not implemented`). Use a POSIX-fs worktree (e.g. `/tmp`) for any tooling, then copy sources back.
3. Go 1.27+ required for `gateway-native` (installed at `/usr/local/go` in the dev sandbox).

## Toolchain (pinned)

| Tool | Version |
|---|---|
| JDK | 17 (Temurin in CI) |
| Gradle | 8.9 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 (+ compose compiler plugin, same version) |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Go | 1.27.x |
| NDK (CI only) | r27 |

## Commands

Local (Go parts only — safe here):
```bash
cd gateway-native && go vet ./... && go test -count=1 ./...
go build -o /tmp/harness ./cmd/harness && /tmp/harness   # real-internet e2e suite
```

CI (Android + AAR): push or open a PR; workflows live in `.github/workflows/`.
- `android.yml` → unit tests + debug APK artifact
- `gateway-native.yml` → go vet/test + gomobile AAR artifact (needs NDK job)

## Regenerating the Gradle wrapper

The wrapper IS committed. If it needs regeneration, do it on a POSIX filesystem with JDK 17:
```bash
gradle wrapper --gradle-version 8.9 --no-daemon
```
(Gradle ≥9 runs on newer JVMs; Gradle 8.x must be launched with JDK ≤21.)

## Mock-first rule

Phase 2 UI runs entirely on `MockHmxEngine`. Never import mock classes from networking code paths; real engines will implement the same machine-driving interfaces later. The Home screen exposes a `debug` button to inject failure scenarios (pairing expired, VPN denied, handshake fail, provider offline…) so every error state is reachable before real networking exists.

# LinkToMac

A macOS companion app for Android phones — notifications, call logs, messages, photos, and screen mirroring, inspired by Microsoft's Phone Link.

Android-only by design: iOS does not expose the APIs (notification mirroring, call log/SMS access, screen capture) this app relies on to third-party apps.

## Status

**Phases 1–3 complete and verified end-to-end on real hardware:** device pairing, LAN discovery/reconnect, an encrypted session channel, native macOS notification mirroring, call log sync, SMS thread sync + reply-from-Mac, and a photo grid (month-grouped, paginated, on-demand full-res, live refresh when the phone's library changes). The main app window has Phone Link-style sidebar navigation — Notifications, Calls, Messages, Photos, and This Device are functional; Files, Contacts, and Screen Mirroring are placeholders (Screen Mirroring is on the roadmap as Phase 4; Files/Contacts aren't currently planned, just present for visual parity with a reference design). Next up: Phase 4 (screen mirroring).

See [`docs/PLAN.md`](docs/PLAN.md) for the full roadmap and [`docs/PROTOCOL.md`](docs/PROTOCOL.md) for the wire protocol.

## Structure

```
mac-app/        SwiftUI menu-bar app (Swift Package Manager)
android-app/    Kotlin companion app (Gradle)
docs/           Protocol and planning docs
```

## Mac app

Requires Xcode 16 / Swift 6 toolchain, macOS 14+.

```bash
cd mac-app
Scripts/run.sh
```

**Don't use `swift run` or Xcode's Run button directly** — a bare, bundle-less executable gets registered by Launch Services as `BackgroundOnly`, which means `MenuBarExtra` never gets a real WindowServer session and its status item silently never appears (no crash, no error — it just doesn't show up). `Scripts/run.sh` builds the executable, wraps it in a minimal ad-hoc signed `LinkToMac.app` (with a real bundle identifier and `LSUIElement` set so there's no Dock icon), and opens that instead. This also makes native notification banners work, since `UserNotifications` has the same bundle-identity requirement. Pass `release` as an argument for a release build (`Scripts/run.sh release`).

## Android app

Requires Android Studio (recommended — it manages its own SDK and JDK) or a manually installed Android SDK + JDK 17/21 on `PATH`. The bundled Gradle wrapper (pinned to Gradle 9.4.1 + AGP 8.6.0) has been verified to configure correctly; it stops at the expected `SDK location not found` error without a real Android SDK present, which Android Studio resolves automatically on first open.

```bash
cd android-app
./gradlew assembleDebug
```

Install on a device, open the app, grant notification-listener access, and scan the pairing QR code shown in the Mac app's menu-bar popover.

## Pairing & security

1. The Mac app has one stable P-256 identity key pair (generated once, persisted) and renders a QR code with its LAN address, device id, and a one-time pairing token — deliberately *not* its public key, which would make the QR too dense to scan reliably (see [`docs/PROTOCOL.md`](docs/PROTOCOL.md)).
2. The Android app scans the QR, opens a WebSocket to the Mac, and generates a fresh ephemeral P-256 key pair for the session. The Mac immediately sends its public key over that socket (`serverHello`). Both sides then derive a shared AES-256-GCM session key via ECDH + HKDF (salted with the pairing token). No key material or long-term secret ever crosses the wire.
3. All messages after that handshake are encrypted with the session key.
4. On successful pairing, the Mac issues a persistent device token; the Android app stores it in `EncryptedSharedPreferences` (Android Keystore-backed) and presents it on reconnect (discovered via Bonjour/NSD) so you don't have to re-scan the QR every time. Each reconnect still performs a fresh ECDH exchange for forward secrecy, and pins the Mac's public key against the one learned during original pairing — the device token only skips the manual approval step.
5. The WebSocket transport itself is plain `ws://` (cleartext), not `wss://`/TLS — Android's default network security policy blocks that, so the app opts in via `android:usesCleartextTraffic="true"`. This is intentional, not an oversight: security comes from the app-layer ECDH+AES-GCM encryption in steps 2–3, not from transport TLS, and traffic never leaves the LAN by design. Standing up a real TLS listener would need a self-signed cert generated and pinned on first pairing for no real security gain here.

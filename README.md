# LinkToMac

A macOS companion app for Android phones — notifications, call logs, messages, photos, and screen mirroring, inspired by Microsoft's Phone Link.

Android-only by design: iOS does not expose the APIs (notification mirroring, call log/SMS access, screen capture) this app relies on to third-party apps.

## Status

**Phase 1 in progress:** device pairing, LAN discovery, an encrypted session channel, and notification mirroring (Android → Mac).

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
swift run
```

This runs the menu-bar app from the terminal for development. Native notification banners (`UserNotifications`) require the app to run as a signed `.app` bundle to register with the system — that packaging step is planned for the Polish phase. Until then, incoming notifications are visible in the in-app popover list.

## Android app

Requires Android Studio (recommended — it manages its own SDK and JDK) or a manually installed Android SDK + JDK 17/21 on `PATH`. The bundled Gradle wrapper (pinned to Gradle 9.4.1 + AGP 8.6.0) has been verified to configure correctly; it stops at the expected `SDK location not found` error without a real Android SDK present, which Android Studio resolves automatically on first open.

```bash
cd android-app
./gradlew assembleDebug
```

Install on a device, open the app, grant notification-listener access, and scan the pairing QR code shown in the Mac app's menu-bar popover.

## Pairing & security

1. The Mac app generates an ephemeral P-256 key pair and a one-time pairing token, and renders them as a QR code alongside its LAN address.
2. The Android app scans the QR, generates its own ephemeral key pair, and both sides derive a shared AES-256-GCM session key via ECDH + HKDF (salted with the pairing token). No key material or long-term secret ever crosses the wire.
3. All messages after the initial handshake are encrypted with that session key.
4. On successful pairing, the Mac issues a persistent device token; the Android app stores it in `EncryptedSharedPreferences` (Android Keystore-backed) and presents it on reconnect (discovered via Bonjour/NSD) so you don't have to re-scan the QR every time. Each reconnect still performs a fresh ECDH exchange for forward secrecy — the device token only skips the manual approval step.

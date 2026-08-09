# Roadmap

1. **Foundations** — pairing, encrypted session channel, LAN discovery. ✅ done
2. **Notifications** — `NotificationListenerService` → native banners, dismiss-sync both directions. ✅ done
3. **Calls + Messages** — call log list, SMS thread view, reply-from-Mac. ✅ done
4. **Photos** — thumbnail grid, on-demand full-res fetch, optional Photos.app import. *(next)*
5. **Screen mirroring** — `MediaProjection` capture → H.264 encode → stream → `VideoToolbox` decode/render, plus input relay via `AccessibilityService`.
6. **Polish** — proper signed `.app` bundle + notarization, connection resilience, battery-optimization onboarding, settings, notification actions, UI pass.

## Notes from building Phase 2

- **OEM `ContentProvider` quirks are real.** Samsung's call log/SMS providers reject `LIMIT n` appended to the SQL sort-order string (`IllegalArgumentException: Invalid token LIMIT`) even though that works against stock AOSP. Cap result counts in Kotlin after the query instead of in SQL.
- **`ContentResolver.registerContentObserver` throws `SecurityException` if the permission isn't granted** — unlike `query()`, which just returns null. Both call sites need the same defensive handling, or a missing permission crashes the whole service on `onCreate()`.

## Tech stack

| Layer | Mac | Android |
|---|---|---|
| UI | Swift + SwiftUI | Kotlin + Jetpack Compose |
| Transport | `Network.framework` WebSocket (`NWProtocolWebSocket`) | OkHttp WebSocket |
| Discovery | Bonjour (`NWListener` service advertisement) | NSD (`NsdManager`) |
| Crypto | CryptoKit: P-256 ECDH, HKDF, AES-GCM | `java.security`/`javax.crypto`: EC ECDH, HKDF (RFC 5869 via HMAC), AES-GCM |
| Messages | `Codable` JSON | `kotlinx.serialization` JSON |
| Pairing transport | QR (CoreImage `CIQRCodeGenerator`) | ZXing (`zxing-android-embedded`) |
| Secrets storage | Keychain (planned) / JSON in Application Support (interim) | `EncryptedSharedPreferences` |

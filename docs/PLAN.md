# Roadmap

1. **Foundations** — pairing, encrypted session channel, LAN discovery. ✅ done
2. **Notifications** — `NotificationListenerService` → native banners, dismiss-sync both directions. ✅ done
3. **Calls + Messages** — call log list, SMS thread view, reply-from-Mac. ✅ done
4. **Photos** — thumbnail grid, on-demand full-res fetch. ✅ done (Photos.app import not built — out of scope for now)
5. **Screen mirroring** — `MediaProjection` capture → H.264 encode → stream → `VideoToolbox` decode/render, plus input relay via `AccessibilityService`. *(next)*
6. **Polish** — proper signed `.app` bundle + notarization, connection resilience, battery-optimization onboarding, settings, notification actions, UI pass.

## Notes from building Phase 2

- **OEM `ContentProvider` quirks are real.** Samsung's call log/SMS providers reject `LIMIT n` appended to the SQL sort-order string (`IllegalArgumentException: Invalid token LIMIT`) even though that works against stock AOSP. Cap result counts in Kotlin after the query instead of in SQL.
- **`ContentResolver.registerContentObserver` throws `SecurityException` if the permission isn't granted** — unlike `query()`, which just returns null. Both call sites need the same defensive handling, or a missing permission crashes the whole service on `onCreate()`.

## Notes from building Phase 3

- The main app window (opened by clicking "LinkToMac" in the menu bar popover) now has Phone Link-style sidebar navigation: a device status card (name, connection state, battery) pinned at top, a scrollable nav list, and Settings pinned at bottom. Files and Contacts are in the nav as honest placeholders — not on the roadmap, added to match a reference design the user shared, no backend planned yet.
- Photos use paginated request/response rather than the full-snapshot model calls/SMS use — a library can hold thousands of items, so resending everything on every change doesn't scale. The Mac drives paging explicitly; full-resolution images are fetched one at a time, on demand.
- First cut shipped without any way to detect photo library changes, so deleting a photo on the phone left a stale thumbnail on the Mac with no path to correct itself. Fixed by having Android observe `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (debounced, same ContentObserver pattern as calls/SMS) and push a `photo.libraryChanged` ping; the Mac responds with a hard reset — clear what's loaded, re-page from 0. Loses scroll position on every change, but avoids diffing thumbnail ids against a potentially huge library. Worth revisiting if this ever needs to feel less jarring.

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

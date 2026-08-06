# Roadmap

1. **Foundations** — pairing, encrypted session channel, LAN discovery. *(this phase)*
2. **Notifications** — `NotificationListenerService` → Mac popover / native banners, dismiss-sync both directions. *(this phase)*
3. **Calls + Messages** — call log list, SMS thread view, reply-from-Mac.
4. **Photos** — thumbnail grid, on-demand full-res fetch, optional Photos.app import.
5. **Screen mirroring** — `MediaProjection` capture → H.264 encode → stream → `VideoToolbox` decode/render, plus input relay via `AccessibilityService`.
6. **Polish** — proper signed `.app` bundle + notarization, connection resilience, battery-optimization onboarding, settings, notification actions, UI pass.

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

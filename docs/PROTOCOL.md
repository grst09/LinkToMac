# Wire Protocol (v1)

Transport: WebSocket over TCP on the LAN, port 53821 by default, advertised via Bonjour/NSD as service type `_linktomac._tcp`.

## Identity keys

The Mac holds one **stable** P-256 identity key pair, generated once on first launch and persisted. The Android side uses a **fresh ephemeral** P-256 key pair for every connection (no long-term Android key needed).

The Mac's public key deliberately does **not** travel in the QR code. An early version put it there and the payload — host, port, a 65-byte P-256 point, a pairing token, and a UUID, ~247 bytes — forced a ~77×77-module QR code that phones struggled to scan reliably. Instead, the Mac sends its public key over the socket via `serverHello` (below), keeping the QR under ~100 bytes.

## Handshake

1. First pairing only: Mac generates a random 16-byte `pairingToken` and renders a QR code encoding:
   ```json
   {"host": "192.168.1.23", "port": 53821, "pairingToken": "<base64>", "macDeviceId": "<uuid>"}
   ```
   On a *reconnect*, Android already knows `macDeviceId` from the original pairing (or freshly from the Bonjour TXT record) and skips the QR step entirely.
2. Android opens a WebSocket to `host:port` and generates a fresh ephemeral P-256 key pair `(androidPriv, androidPub)` for this connection, but does not send anything yet.
3. Mac sends a `serverHello` frame **unencrypted**, immediately, on every new connection (both first pairing and reconnect):
   ```json
   {"type": "serverHello", "payload": {
     "macPublicKey": "<base64, stable identity key>",
     "macDeviceId": "<uuid>",
     "macDeviceName": "Ravi's MacBook Pro"
   }}
   ```
4. Android checks `macDeviceId` against what it expected (from the QR, or from its paired-device record on reconnect). On a *reconnect*, it additionally pins `macPublicKey` against the value learned during original pairing — mismatch means a possible MITM or a reinstalled Mac app, and the connection is aborted. On a *first pairing*, the key is trusted on first use (the QR scan itself is the human-verified trust anchor) and persisted for future pinning.
5. Android sends a `hello` frame **unencrypted**:
   ```json
   {"type": "hello", "payload": {
     "androidPublicKey": "<base64>",
     "pairingToken": "<base64, first pairing only>",
     "deviceToken": "<base64, reconnect only>",
     "deviceId": "<uuid>",
     "deviceName": "Pixel 8",
     "protocolVersion": 1
   }}
   ```
6. Salt selection: for a first pairing, `salt = pairingToken`. For a reconnect, `salt = pairingSalt` — the original pairing token, which both sides kept from step 1 (Android in its encrypted device store, Mac in its paired-devices record) — looked up via the presented `deviceToken`.
7. Both sides compute `sharedSecret = ECDH(macIdentityPriv, androidEphemeralPub)` (Mac side) / `ECDH(androidEphemeralPriv, macIdentityPub)` (Android side — same point) and derive `sessionKey = HKDF-SHA256(sharedSecret, salt, info: "LinkToMac-v1", length: 32)`.
8. Mac validates the pairing token (new pairing) or device token (reconnect). If valid, it replies with an **encrypted** `helloAck`:
   ```json
   {"type": "helloAck", "payload": {
     "status": "paired",
     "deviceToken": "<base64, issued once on first pairing>",
     "macDeviceName": "Ravi's MacBook Pro"
   }}
   ```
   If invalid/rejected: `{"status": "rejected"}` and the connection is closed.

**Implementation note:** kotlinx.serialization omits fields that equal their Kotlin default (e.g. `protocolVersion = 1`, `actions = emptyList()`) unless the `Json` instance is configured with `encodeDefaults = true`. Swift's `Codable`, on the other hand, requires non-optional fields to be present in the JSON. The Android encoder must set `encodeDefaults = true` or these payloads will fail to decode on the Mac.

## Encrypted envelope

Every frame after the handshake is:

```json
{"nonce": "<base64, 12 bytes>", "ciphertext": "<base64, AES-256-GCM(sessionKey, nonce, plaintext)>"}
```

`plaintext` is a JSON object `{"type": "...", "payload": {...}}`. AES-GCM's tag provides integrity; a fresh random nonce is generated per message.

## Message types (Phase 1)

| type | direction | payload |
|---|---|---|
| `serverHello` / `hello` / `helloAck` | see above | see above |
| `notification.posted` | Android → Mac | `{id, packageName, appName, title, text, subText, category, postedAt, actions: [{title, actionId}]}` |
| `notification.removed` | Android → Mac | `{id}` |
| `notification.dismiss` | Mac → Android | `{id}` (user dismissed on Mac; Android cancels it on the phone) |
| `ping` / `pong` | either | `{}` — keepalive, sent every 20s; connection considered dead after 45s of silence |

## Message types (Phase 2)

Call log and SMS both use a **full-snapshot-on-change** model rather than incremental add/update/remove messages: Android sends its entire recent call log / SMS thread set whenever anything changes (new call, new/read message), capped at a bounded count. This avoids reconciliation logic on either side — the Mac just replaces its local copy — at the cost of re-sending some unchanged data on every update. Reasonable for call log/SMS volumes; would need revisiting if this ever needs to scale to years of history.

| type | direction | payload |
|---|---|---|
| `call.sync` | Android → Mac | `{calls: [CallLogEntry]}` — sent once right after `helloAck`, and again on any call log change |
| `sms.sync` | Android → Mac | `{threads: [SmsThread]}` — sent once right after `helloAck`, and again on any SMS change |
| `sms.send` | Mac → Android | `{address, body}` — Android sends via `SmsManager`; result isn't separately ack'd, the next `sms.sync` reflects it |

```
CallLogEntry = {id, number, contactName?, type, date, durationSeconds}
  type: "incoming" | "outgoing" | "missed" | "rejected" | "blocked" | "voicemail" | "unknown"
  date: epoch milliseconds

SmsThread = {threadId, address, contactName?, messages: [SmsMessage]}
SmsMessage = {id, address, body, date, isOutgoing}
  date: epoch milliseconds
```

`contactName` is resolved via `ContactsContract.PhoneLookup` when `READ_CONTACTS` is granted; omitted (falls back to the raw number in the UI) otherwise. MMS is out of scope — `SmsThread`/`SmsMessage` only cover the `Telephony.Sms` text-message table.

## Message types (Phase 3)

Photos use a **paginated request/response** model instead — full-snapshot doesn't work once a library can hold thousands of items. The Mac drives paging explicitly. Full-resolution images are fetched on demand, one at a time, only when the user opens a photo.

There's no request-id/correlation scheme: each side keeps at most one photo request in flight (the Mac disables "Load More" / photo taps while a response is pending), so responses are matched to requests by ordering rather than an explicit id. `photo.full`'s echoed `id` lets the Mac at least discard a stale response if the user already moved on to a different photo.

Unlike full-resolution fetches, library changes (a photo added or deleted) *are* pushed — Android observes `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (debounced, same pattern as the call log/SMS `ContentObserver`s) and sends `photo.libraryChanged` with no payload. The Mac's response is a hard reset: clear whatever's loaded and re-request from offset 0. This loses scroll position on every change, which is a real UX cost, but avoids diffing thumbnail ids against a library that can be thousands of items long — correctness over polish for a first version.

| type | direction | payload |
|---|---|---|
| `photo.pageRequest` | Mac → Android | `{offset, limit}` |
| `photo.page` | Android → Mac | `{photos: [PhotoThumbnail], hasMore}` |
| `photo.fullRequest` | Mac → Android | `{id}` |
| `photo.full` | Android → Mac | `{id, dataBase64, mimeType}` |
| `photo.libraryChanged` | Android → Mac | `{}` — debounced; Mac resets and re-pages from 0 |
| `device.status` | Android → Mac | `{batteryPercent, isCharging}` — sent once right after `helloAck`, and again whenever `ACTION_BATTERY_CHANGED` fires |

```
PhotoThumbnail = {id, takenAt, thumbnailBase64}
  id: the MediaStore row id, as a string
  takenAt: epoch milliseconds
  thumbnailBase64: JPEG, longest edge ~300px, quality ~60 — kept small since a page (30 photos)
    ships in one WebSocket frame
```

Photo queries also avoid `LIMIT`/`OFFSET` in the SQL sort order, for the same OEM-provider-compatibility reason as call log/SMS (see PLAN.md) — offset/limit are applied in Kotlin after fetching the full sorted id list.

## Message types (Phase 4)

Screen mirroring splits into a **control plane** (JSON, the existing encrypted envelope — start/stop/config/input) and a **data plane** (raw binary WebSocket frames — the H.264 video itself). Video frames are not JSON: at 30fps, base64-encoding every frame and wrapping it in a JSON envelope would add ~33% size overhead plus per-frame string/parse overhead, multiplied 30-60 times a second. `NWProtocolWebSocket` and OkHttp's WebSocket both support binary frames natively alongside text, so video rides the same connection and port, just a different opcode.

**Binary frame format** (Android → Mac only): `nonce (12 bytes) || AES-256-GCM(sessionKey, nonce, raw H.264 NAL unit bytes)`. The NAL bytes are exactly what `MediaCodec`'s encoder output buffer contains — Annex-B format (`00 00 00 01`-prefixed) — unmodified before encryption. No JSON, no envelope `type` field; a binary WebSocket opcode *is* the type. The same AES-GCM session key and encrypted framing as the JSON control channel — this isn't a lower-security fast path, just a different serialization.

**Control messages** (JSON, existing envelope):

| type | direction | payload |
|---|---|---|
| `mirror.start` | Mac → Android | `{}` — triggers the MediaProjection permission flow if not already granted this session (Android *requires* bringing an Activity to the foreground for this — a real UX interruption with no way around it without root) |
| `mirror.stop` | Mac → Android | `{}` |
| `mirror.config` | Android → Mac | `{width, height, fps, spsBase64, ppsBase64}` — sent once, right after capture actually starts, before any binary video frames. `sps`/`pps` (no start codes) let the Mac build a `CMFormatDescription` via `CMVideoFormatDescriptionCreateFromH264ParameterSets` before it needs to decode anything |
| `mirror.stopped` | Android → Mac | `{reason}` — `"requested"` \| `"permission_denied"` \| `"error"` |
| `mirror.tap` | Mac → Android | `{x, y}` — normalized 0.0–1.0, relative to the phone's screen, so the Mac's rendered view size never has to match phone resolution |
| `mirror.swipe` | Mac → Android | `{startX, startY, endX, endY, durationMs}` — normalized |
| `mirror.key` | Mac → Android | `{action}` — `"back"` \| `"home"` \| `"recents"`, the only reliable non-touch actions achievable via `AccessibilityService.performGlobalAction` without root |
| `mirror.textInput` | Mac → Android | `{text}` — sets clipboard + dispatches `ACTION_PASTE` on the currently-focused editable `AccessibilityNodeInfo`. This is a real limitation, not a design choice: without root or being the system IME, an app cannot inject arbitrary raw key events (`Instrumentation.sendKeySync` needs a signature-level permission); paste-into-focused-field is the closest equivalent available to a normal accessibility service |

Android requires the input-injection accessibility service to be enabled manually via Settings (same pattern as notification-listener access in Phase 1) — it can't be granted programmatically.

## Message types (Phase 5)

Shared clipboard, text only (no images/files) — modeled on iPhone/Mac Universal Clipboard, but the two platforms aren't symmetric because of an OS-level restriction, not a design choice:

| type | direction | payload |
|---|---|---|
| `clipboard.update` | either direction | `{text, sourceDeviceId, timestamp}` — sent whenever a local copy is detected on either side. The receiving side writes it straight into its own system clipboard |

**Mac → Android** works fully in the background: `ConnectionServer` polls `NSPasteboard.changeCount` every second (macOS has no clipboard-change notification API) and pushes text copies whenever the phone is connected; Android's `SyncForegroundService` calls `ClipboardManager.setPrimaryClip` the instant an update arrives, no foreground Activity needed — writing to the clipboard isn't restricted while backgrounded, only reading is.

**Android → Mac** only fires while the LinkToMac Android app is focused. Since Android 10, `ClipboardManager.getPrimaryClip()` returns nothing for an app that isn't the default IME or currently focused — a background service (even a foreground-service-with-notification like `SyncForegroundService`) can't read clipboard content, full stop. `MainActivity` registers `OnPrimaryClipChangedListener` across `onResume`/`onPause` and checks the clipboard once on resume (in case it changed while the app wasn't running) — so a copy made elsewhere on the phone reaches the Mac once LinkToMac is opened/foregrounded, not the instant it happens like the Mac→Android direction.

Both sides track the last-synced text and skip re-sending it, so applying a remote update doesn't bounce straight back to its source as if it were a new local copy.

Future phases: none currently planned beyond Phase 4.

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

Future phases add `photo.*` and `mirror.*` message families on the same envelope.

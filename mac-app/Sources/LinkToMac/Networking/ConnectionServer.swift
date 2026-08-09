import Foundation
import Network
import CryptoKit
import Observation

/// Deliberately minimal: the Mac's public key is *not* included here. Cramming a 65-byte
/// P-256 point into the QR (on top of the token and device id) pushed the payload past ~247
/// bytes, forcing a dense ~77x77-module QR code that phones struggled to scan reliably. The
/// public key now travels over the socket instead, via `serverHello` — see docs/PROTOCOL.md.
struct PairingQRPayload: Codable {
    let host: String
    let port: UInt16
    let pairingToken: String
    let macDeviceId: String
}

/// Runs the WebSocket + Bonjour server the Android app connects to, handles the
/// handshake described in docs/PROTOCOL.md, and dispatches decrypted messages.
@Observable
final class ConnectionServer {
    static let port: UInt16 = 53821
    static let serviceType = "_linktomac._tcp"
    static let defaultPhotoPageSize = 30

    enum ConnectionState: Equatable {
        case idle
        case listening
        case connected(deviceName: String)
        case failed(String)
    }

    private(set) var state: ConnectionState = .idle
    private(set) var pendingPairing: PairingQRPayload?

    private let identity: IdentityStore
    private let pairedDeviceStore: PairedDeviceStore
    let notificationStore: NotificationStore
    let callLogStore: CallLogStore
    let messageStore: MessageStore
    let photoStore: PhotoStore
    let deviceStatusStore: DeviceStatusStore

    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private var sessionKey: SymmetricKey?
    private var activePairingToken: Data?

    init(
        identity: IdentityStore,
        pairedDeviceStore: PairedDeviceStore,
        notificationStore: NotificationStore,
        callLogStore: CallLogStore,
        messageStore: MessageStore,
        photoStore: PhotoStore,
        deviceStatusStore: DeviceStatusStore
    ) {
        self.identity = identity
        self.pairedDeviceStore = pairedDeviceStore
        self.notificationStore = notificationStore
        self.callLogStore = callLogStore
        self.messageStore = messageStore
        self.photoStore = photoStore
        self.deviceStatusStore = deviceStatusStore
    }

    func start() {
        do {
            let params = NWParameters.tcp
            let wsOptions = NWProtocolWebSocket.Options()
            wsOptions.autoReplyPing = true
            params.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

            let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: Self.port)!)
            var txt = NWTXTRecord()
            txt["pk"] = identity.publicKeyBase64
            txt["id"] = identity.deviceId
            listener.service = NWListener.Service(name: Host.current().localizedName ?? "LinkToMac", type: Self.serviceType, txtRecord: txt.data)

            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            listener.stateUpdateHandler = { [weak self] newState in
                switch newState {
                case .ready:
                    self?.state = .listening
                case .failed(let error):
                    self?.state = .failed(error.localizedDescription)
                default:
                    break
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// Generates a new one-time pairing token/QR payload. Call this when the user opens the
    /// pairing UI; the token is single-use and only valid until the next successful pairing
    /// or until a new session is started.
    func beginNewPairingSession() -> PairingQRPayload {
        let tokenBytes = SymmetricKey(size: .bits128).withUnsafeBytes { Data($0) }
        activePairingToken = tokenBytes
        let payload = PairingQRPayload(
            host: LocalNetwork.primaryIPv4Address() ?? "0.0.0.0",
            port: Self.port,
            pairingToken: tokenBytes.base64EncodedString(),
            macDeviceId: identity.deviceId
        )
        pendingPairing = payload
        return payload
    }

    // MARK: - Connection handling

    private func accept(_ connection: NWConnection) {
        // Single active connection for now; a later phase can support multiple paired phones.
        activeConnection?.cancel()
        activeConnection = connection
        sessionKey = nil

        connection.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            switch newState {
            case .ready:
                self.sendServerHello(on: connection)
                self.receiveNext(on: connection)
            case .failed, .cancelled:
                self.handleDisconnect()
            default:
                break
            }
        }
        connection.start(queue: .main)
    }

    private func sendServerHello(on connection: NWConnection) {
        let payload = ServerHelloPayload(
            macPublicKey: identity.publicKeyBase64,
            macDeviceId: identity.deviceId,
            macDeviceName: Host.current().localizedName ?? "Mac"
        )
        guard let message = try? Message(type: "serverHello", payload: JSONValue.from(payload)),
              let data = try? JSONEncoder().encode(message) else { return }
        sendRaw(data, on: connection)
    }

    private func handleDisconnect() {
        activeConnection = nil
        sessionKey = nil
        if case .connected = state {
            state = .listening
        }
    }

    private func receiveNext(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, isComplete {
                self.handleIncoming(data, on: connection)
            }
            if error == nil {
                self.receiveNext(on: connection)
            }
        }
    }

    private func handleIncoming(_ data: Data, on connection: NWConnection) {
        do {
            if let key = sessionKey {
                let frame = try JSONDecoder().decode(EncryptedFrame.self, from: data)
                let plaintext = try SecureChannel.open(frame, using: key)
                try dispatch(plaintext, on: connection)
            } else {
                let message = try JSONDecoder().decode(Message.self, from: data)
                guard message.type == "hello" else { return }
                let hello = try message.payload.decoded(as: HelloPayload.self)
                try handleHello(hello, on: connection)
            }
        } catch {
            print("LinkToMac: failed to handle incoming frame: \(error)")
        }
    }

    private func handleHello(_ hello: HelloPayload, on connection: NWConnection) throws {
        let salt: Data
        let isNewPairing: Bool

        if let pairingToken = hello.pairingToken, let activePairingToken, Data(base64Encoded: pairingToken) == activePairingToken {
            salt = activePairingToken
            isNewPairing = true
        } else if let deviceToken = hello.deviceToken,
                  let existing = pairedDeviceStore.device(withToken: deviceToken),
                  existing.id == hello.deviceId,
                  let saltData = Data(base64Encoded: existing.pairingSalt) {
            salt = saltData
            isNewPairing = false
        } else {
            sendUnencryptedReject(on: connection)
            return
        }

        let key = try SecureChannel.deriveSessionKey(
            privateKey: identity.privateKey,
            peerPublicKeyBase64: hello.androidPublicKey,
            salt: salt
        )
        sessionKey = key

        let deviceToken = isNewPairing ? SymmetricKey(size: .bits256).withUnsafeBytes { Data($0).base64EncodedString() } : hello.deviceToken!
        pairedDeviceStore.upsert(id: hello.deviceId, deviceName: hello.deviceName, deviceToken: deviceToken, pairingSalt: salt.base64EncodedString())
        if isNewPairing {
            activePairingToken = nil
            pendingPairing = nil
        }

        let ack = HelloAckPayload(status: "paired", deviceToken: deviceToken, macDeviceName: Host.current().localizedName ?? "Mac")
        try send(type: "helloAck", payload: ack, on: connection)
        state = .connected(deviceName: hello.deviceName)
    }

    private func sendUnencryptedReject(on connection: NWConnection) {
        let payload = HelloAckPayload(status: "rejected", deviceToken: nil, macDeviceName: Host.current().localizedName ?? "Mac")
        guard let message = try? Message(type: "helloAck", payload: JSONValue.from(payload)),
              let data = try? JSONEncoder().encode(message) else { return }
        sendRaw(data, on: connection)
        connection.cancel()
    }

    private func dispatch(_ plaintext: Data, on connection: NWConnection) throws {
        let message = try JSONDecoder().decode(Message.self, from: plaintext)
        switch message.type {
        case "notification.posted":
            let payload = try message.payload.decoded(as: NotificationPostedPayload.self)
            notificationStore.add(payload)
        case "notification.removed":
            let payload = try message.payload.decoded(as: NotificationRemovedPayload.self)
            notificationStore.remove(id: payload.id)
        case "call.sync":
            let payload = try message.payload.decoded(as: CallLogSyncPayload.self)
            callLogStore.update(payload.calls)
        case "sms.sync":
            let payload = try message.payload.decoded(as: SmsSyncPayload.self)
            messageStore.update(payload.threads)
        case "photo.page":
            let payload = try message.payload.decoded(as: PhotoPagePayload.self)
            photoStore.appendPage(payload.photos, hasMore: payload.hasMore)
        case "photo.full":
            let payload = try message.payload.decoded(as: PhotoFullPayload.self)
            if let data = Data(base64Encoded: payload.dataBase64) {
                photoStore.setFullImage(id: payload.id, data: data)
            }
        case "device.status":
            let payload = try message.payload.decoded(as: DeviceStatusPayload.self)
            deviceStatusStore.update(payload)
        case "photo.libraryChanged":
            photoStore.invalidate()
            requestPhotoPage(offset: 0, limit: Self.defaultPhotoPageSize)
        case "ping":
            try send(type: "pong", payload: EmptyPayload(), on: connection)
        default:
            break
        }
    }

    // MARK: - Sending

    /// Call when the user dismisses a notification in the Notifications window, to mirror
    /// the dismissal back to the phone.
    func sendDismiss(id: String) {
        guard let connection = activeConnection else { return }
        try? send(type: "notification.dismiss", payload: NotificationRemovedPayload(id: id), on: connection)
    }

    /// Ask the phone to send a text via its own SmsManager. Not separately ack'd — the next
    /// `sms.sync` from the phone reflects whether it went out.
    func sendSms(address: String, body: String) {
        guard let connection = activeConnection else { return }
        try? send(type: "sms.send", payload: SmsSendPayload(address: address, body: body), on: connection)
    }

    /// Requests the next page of photo thumbnails. Caller (PhotosView) is responsible for not
    /// firing another request while one is already in flight — see docs/PROTOCOL.md.
    func requestPhotoPage(offset: Int, limit: Int) {
        guard let connection = activeConnection else { return }
        photoStore.beginLoadingMore()
        try? send(type: "photo.pageRequest", payload: PhotoPageRequestPayload(offset: offset, limit: limit), on: connection)
    }

    func requestPhotoFull(id: String) {
        guard let connection = activeConnection else { return }
        try? send(type: "photo.fullRequest", payload: PhotoFullRequestPayload(id: id), on: connection)
    }

    private func send<T: Encodable>(type: String, payload: T, on connection: NWConnection) throws {
        guard let key = sessionKey else { return }
        let message = Message(type: type, payload: try JSONValue.from(payload))
        let plaintext = try JSONEncoder().encode(message)
        let frame = try SecureChannel.seal(plaintext, using: key)
        let data = try JSONEncoder().encode(frame)
        sendRaw(data, on: connection)
    }

    private func sendRaw(_ data: Data, on connection: NWConnection) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "message", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
            if let error {
                print("LinkToMac: send failed: \(error)")
            }
        })
    }
}

struct EmptyPayload: Codable {}

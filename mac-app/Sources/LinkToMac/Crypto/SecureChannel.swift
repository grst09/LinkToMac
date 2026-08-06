import Foundation
import CryptoKit

/// P-256 ECDH + HKDF-SHA256 + AES-256-GCM session crypto, matching docs/PROTOCOL.md.
///
/// P-256 (not Curve25519) is used specifically so the Android side can rely on the
/// standard `java.security` EC APIs without pulling in a third-party crypto library.
enum SecureChannel {
    struct KeyPair {
        let privateKey: P256.KeyAgreement.PrivateKey
        var publicKeyBase64: String {
            privateKey.publicKey.x963Representation.base64EncodedString()
        }
    }

    static func generateKeyPair() -> KeyPair {
        KeyPair(privateKey: P256.KeyAgreement.PrivateKey())
    }

    /// Derives the AES-GCM session key shared with the peer.
    /// `salt` must be the same pairing token (or stored pairing salt) both sides used.
    static func deriveSessionKey(
        privateKey: P256.KeyAgreement.PrivateKey,
        peerPublicKeyBase64: String,
        salt: Data
    ) throws -> SymmetricKey {
        guard let peerKeyData = Data(base64Encoded: peerPublicKeyBase64) else {
            throw SecureChannelError.malformedPeerKey
        }
        let peerKey = try P256.KeyAgreement.PublicKey(x963Representation: peerKeyData)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerKey)
        return sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("LinkToMac-v1".utf8),
            outputByteCount: 32
        )
    }

    static func seal(_ plaintext: Data, using key: SymmetricKey) throws -> EncryptedFrame {
        let sealedBox = try AES.GCM.seal(plaintext, using: key)
        guard let combined = sealedBox.combined else {
            throw SecureChannelError.sealFailed
        }
        // combined = nonce (12 bytes) || ciphertext || tag (16 bytes)
        let nonce = combined.prefix(12)
        let rest = combined.dropFirst(12)
        return EncryptedFrame(
            nonce: Data(nonce).base64EncodedString(),
            ciphertext: Data(rest).base64EncodedString()
        )
    }

    static func open(_ frame: EncryptedFrame, using key: SymmetricKey) throws -> Data {
        guard let nonceData = Data(base64Encoded: frame.nonce),
              let ciphertextData = Data(base64Encoded: frame.ciphertext) else {
            throw SecureChannelError.malformedFrame
        }
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertextData.dropLast(16), tag: ciphertextData.suffix(16))
        return try AES.GCM.open(sealedBox, using: key)
    }
}

enum SecureChannelError: Error {
    case malformedPeerKey
    case sealFailed
    case malformedFrame
}

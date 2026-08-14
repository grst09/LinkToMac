//! P-256 ECDH + HKDF-SHA256 + AES-256-GCM session crypto, ported from
//! `mac-app/Sources/LinkToMac/Crypto/SecureChannel.swift`. See docs/PROTOCOL.md.
//!
//! P-256 (not Curve25519) is used specifically so the Android side can rely on the
//! standard `java.security` EC APIs without pulling in a third-party crypto library.

use aes_gcm::aead::{Aead, Generate, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use hkdf::Hkdf;
use p256::ecdh::diffie_hellman;
use p256::elliptic_curve::sec1::ToSec1Point;
use p256::{PublicKey, SecretKey};
use sha2::Sha256;

use crate::protocol::envelope::EncryptedFrame;

const HKDF_INFO: &[u8] = b"LinkToMac-v1";
const SESSION_KEY_LEN: usize = 32;

#[derive(Debug, thiserror::Error)]
pub enum SecureChannelError {
    #[error("malformed peer public key")]
    MalformedPeerKey,
    #[error("malformed encrypted frame")]
    MalformedFrame,
    #[error("seal failed")]
    SealFailed,
    #[error("open failed (bad key, tag mismatch, or corrupted ciphertext)")]
    OpenFailed,
    #[error("base64 decode error: {0}")]
    Base64(#[from] base64::DecodeError),
}

/// A P-256 key agreement keypair. Used both for the Mac's stable identity key and (were we
/// ever to need it) an ephemeral session key — this app only ever generates the former.
pub struct KeyPair {
    pub secret_key: SecretKey,
}

impl KeyPair {
    pub fn generate() -> Self {
        Self {
            secret_key: SecretKey::generate(),
        }
    }

    pub fn from_raw_bytes(raw: &[u8]) -> Result<Self, SecureChannelError> {
        let secret_key =
            SecretKey::from_slice(raw).map_err(|_| SecureChannelError::MalformedPeerKey)?;
        Ok(Self { secret_key })
    }

    pub fn raw_bytes(&self) -> Vec<u8> {
        self.secret_key.to_bytes().to_vec()
    }

    /// X9.63 uncompressed point encoding (0x04 || X || Y, 65 bytes) — matches CryptoKit's
    /// `x963Representation` for an uncompressed P-256 public key exactly (same format, SEC1
    /// uncompressed == X9.63 uncompressed).
    pub fn public_key_base64(&self) -> String {
        let public_key = self.secret_key.public_key();
        let encoded = public_key.to_sec1_point(false);
        BASE64.encode(encoded.as_bytes())
    }
}

/// Derives the AES-GCM session key shared with the peer. `salt` must be the same pairing
/// token (or stored pairing salt) both sides used.
pub fn derive_session_key(
    secret_key: &SecretKey,
    peer_public_key_base64: &str,
    salt: &[u8],
) -> Result<[u8; SESSION_KEY_LEN], SecureChannelError> {
    let peer_key_bytes = BASE64.decode(peer_public_key_base64)?;
    let peer_public_key = PublicKey::from_sec1_bytes(&peer_key_bytes)
        .map_err(|_| SecureChannelError::MalformedPeerKey)?;

    let shared_secret = diffie_hellman(
        secret_key.to_nonzero_scalar(),
        peer_public_key.as_affine(),
    );

    // HKDF-SHA256(salt, ikm=shared x-coordinate, info="LinkToMac-v1", 32 bytes) — matches
    // CryptoKit's SharedSecret.hkdfDerivedSymmetricKey exactly.
    let hk = Hkdf::<Sha256>::new(Some(salt), shared_secret.raw_secret_bytes().as_slice());
    let mut okm = [0u8; SESSION_KEY_LEN];
    hk.expand(HKDF_INFO, &mut okm)
        .map_err(|_| SecureChannelError::SealFailed)?;
    Ok(okm)
}

fn cipher_for(key: &[u8; SESSION_KEY_LEN]) -> Aes256Gcm {
    Aes256Gcm::new(&Key::<Aes256Gcm>::from(*key))
}

/// JSON-envelope variant: nonce and (ciphertext||tag) each base64-encoded separately, matching
/// `EncryptedFrame`'s two string fields on the wire.
pub fn seal(
    plaintext: &[u8],
    key: &[u8; SESSION_KEY_LEN],
) -> Result<EncryptedFrame, SecureChannelError> {
    let cipher = cipher_for(key);
    let nonce = Nonce::generate();
    let ciphertext = cipher
        .encrypt(&nonce, plaintext)
        .map_err(|_| SecureChannelError::SealFailed)?;
    Ok(EncryptedFrame {
        nonce: BASE64.encode(nonce.as_slice()),
        ciphertext: BASE64.encode(&ciphertext),
    })
}

pub fn open(
    frame: &EncryptedFrame,
    key: &[u8; SESSION_KEY_LEN],
) -> Result<Vec<u8>, SecureChannelError> {
    let nonce_bytes = BASE64.decode(&frame.nonce)?;
    let ciphertext = BASE64.decode(&frame.ciphertext)?;
    if nonce_bytes.len() != 12 {
        return Err(SecureChannelError::MalformedFrame);
    }
    let cipher = cipher_for(key);
    let nonce = Nonce::try_from(nonce_bytes.as_slice())
        .map_err(|_| SecureChannelError::MalformedFrame)?;
    cipher
        .decrypt(&nonce, ciphertext.as_slice())
        .map_err(|_| SecureChannelError::OpenFailed)
}

/// Raw-bytes variant for binary WebSocket frames (screen-mirroring video) — see
/// docs/PROTOCOL.md's Phase 4 binary frame format: `nonce(12) || ciphertext || tag(16)` as one
/// blob, no JSON/base64 wrapper.
pub fn seal_raw(plaintext: &[u8], key: &[u8; SESSION_KEY_LEN]) -> Result<Vec<u8>, SecureChannelError> {
    let cipher = cipher_for(key);
    let nonce = Nonce::generate();
    let ciphertext = cipher
        .encrypt(&nonce, plaintext)
        .map_err(|_| SecureChannelError::SealFailed)?;
    let mut combined = Vec::with_capacity(12 + ciphertext.len());
    combined.extend_from_slice(nonce.as_slice());
    combined.extend_from_slice(&ciphertext);
    Ok(combined)
}

pub fn open_raw(data: &[u8], key: &[u8; SESSION_KEY_LEN]) -> Result<Vec<u8>, SecureChannelError> {
    if data.len() < 12 {
        return Err(SecureChannelError::MalformedFrame);
    }
    let (nonce_bytes, ciphertext) = data.split_at(12);
    let cipher = cipher_for(key);
    let nonce = Nonce::try_from(nonce_bytes).map_err(|_| SecureChannelError::MalformedFrame)?;
    cipher
        .decrypt(&nonce, ciphertext)
        .map_err(|_| SecureChannelError::OpenFailed)
}

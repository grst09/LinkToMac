//! The Mac's stable P-256 identity keypair, generated once and reused across every pairing
//! session and reconnect. Ported from `mac-app/Sources/LinkToMac/Storage/IdentityStore.swift`.
//!
//! Critical constraint (see docs/PLAN.md and the Tauri-rewrite plan): Android pins the Mac's
//! public key after first pairing. If this store ever generated a *fresh* key instead of
//! reusing the old Swift app's key, every already-paired phone would reject reconnection and
//! need a full QR re-pair. So on first launch we look for the old app's identity file and
//! import its raw key bytes verbatim before ever generating a new one.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::crypto::secure_channel::KeyPair;

#[derive(Debug, Serialize, Deserialize)]
struct Record {
    #[serde(rename = "deviceId")]
    device_id: String,
    /// Base64 in JSON (Swift's `Data` serializes to base64 by default) — raw 32-byte P-256
    /// scalar, not PKCS#8/DER-wrapped.
    #[serde(rename = "rawPrivateKey")]
    raw_private_key: String,
}

pub struct IdentityStore {
    pub key_pair: KeyPair,
    pub device_id: String,
    file_path: PathBuf,
}

impl IdentityStore {
    /// `app_data_dir` is this app's own Tauri-resolved data directory (distinct from the old
    /// Swift app's hardcoded `~/Library/Application Support/LinkToMac/`).
    pub fn load_or_create(app_data_dir: &Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(app_data_dir)?;
        let file_path = app_data_dir.join("identity.json");

        if let Some(loaded) = Self::try_load(&file_path) {
            return Ok(loaded);
        }

        // Not found in our own store yet — try importing the old Swift app's identity so
        // already-paired phones don't have to re-pair. macOS-only path; on Linux this is
        // simply never present, so the check is a harmless no-op there.
        if let Some(old_path) = old_swift_app_identity_path() {
            if let Some(migrated) = Self::try_load(&old_path) {
                tracing::info!(
                    "migrated identity from old Swift app at {}",
                    old_path.display()
                );
                let migrated = Self {
                    file_path: file_path.clone(),
                    ..migrated
                };
                migrated.save()?;
                return Ok(migrated);
            }
        }

        let key_pair = KeyPair::generate();
        let device_id = Uuid::new_v4().to_string();
        let created = Self {
            key_pair,
            device_id,
            file_path,
        };
        created.save()?;
        Ok(created)
    }

    fn try_load(path: &Path) -> Option<Self> {
        let data = std::fs::read_to_string(path).ok()?;
        let record: Record = serde_json::from_str(&data).ok()?;
        let raw = base64_decode(&record.raw_private_key)?;
        let key_pair = KeyPair::from_raw_bytes(&raw).ok()?;
        Some(Self {
            key_pair,
            device_id: record.device_id,
            file_path: path.to_path_buf(),
        })
    }

    fn save(&self) -> std::io::Result<()> {
        let record = Record {
            device_id: self.device_id.clone(),
            raw_private_key: base64_encode(&self.key_pair.raw_bytes()),
        };
        let data = serde_json::to_string(&record)?;
        std::fs::write(&self.file_path, data)
    }

    pub fn public_key_base64(&self) -> String {
        self.key_pair.public_key_base64()
    }
}

fn old_swift_app_identity_path() -> Option<PathBuf> {
    if cfg!(target_os = "macos") {
        dirs::home_dir().map(|home| {
            home.join("Library/Application Support/LinkToMac/identity.json")
        })
    } else {
        None
    }
}

fn base64_encode(bytes: &[u8]) -> String {
    use base64::{engine::general_purpose::STANDARD, Engine as _};
    STANDARD.encode(bytes)
}

fn base64_decode(s: &str) -> Option<Vec<u8>> {
    use base64::{engine::general_purpose::STANDARD, Engine as _};
    STANDARD.decode(s).ok()
}

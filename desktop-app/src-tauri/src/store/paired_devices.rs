//! Paired Android device records, ported from
//! `mac-app/Sources/LinkToMac/Storage/PairedDeviceStore.swift`. Same migration reasoning as
//! `identity.rs` — these carry the `deviceToken`/`pairingSalt` Android already trusts, so a
//! fresh empty store would force every paired phone to re-pair via QR.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PairedDevice {
    /// Android deviceId
    pub id: String,
    #[serde(rename = "deviceName")]
    pub device_name: String,
    #[serde(rename = "deviceToken")]
    pub device_token: String,
    /// The original pairing token from QR pairing, reused as the HKDF salt on every reconnect.
    #[serde(rename = "pairingSalt")]
    pub pairing_salt: String,
    #[serde(rename = "pairedAt")]
    pub paired_at: String,
}

pub struct PairedDeviceStore {
    file_path: PathBuf,
    devices: Vec<PairedDevice>,
}

impl PairedDeviceStore {
    pub fn load_or_create(app_data_dir: &Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(app_data_dir)?;
        let file_path = app_data_dir.join("paired-devices.json");

        if let Some(devices) = Self::try_load(&file_path) {
            return Ok(Self { file_path, devices });
        }

        // Import the old Swift app's paired-device records on first launch, same reasoning
        // as IdentityStore's migration.
        if let Some(old_path) = old_swift_app_paired_devices_path() {
            if let Some(devices) = Self::try_load(&old_path) {
                tracing::info!(
                    "migrated {} paired device(s) from old Swift app at {}",
                    devices.len(),
                    old_path.display()
                );
                let store = Self { file_path, devices };
                store.save()?;
                return Ok(store);
            }
        }

        Ok(Self {
            file_path,
            devices: Vec::new(),
        })
    }

    fn try_load(path: &Path) -> Option<Vec<PairedDevice>> {
        let data = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    fn save(&self) -> std::io::Result<()> {
        let data = serde_json::to_string(&self.devices)?;
        std::fs::write(&self.file_path, data)
    }

    pub fn device_with_id(&self, id: &str) -> Option<&PairedDevice> {
        self.devices.iter().find(|d| d.id == id)
    }

    pub fn device_with_token(&self, token: &str) -> Option<&PairedDevice> {
        self.devices.iter().find(|d| d.device_token == token)
    }

    pub fn upsert(
        &mut self,
        id: &str,
        device_name: &str,
        device_token: &str,
        pairing_salt: &str,
    ) -> std::io::Result<PairedDevice> {
        if let Some(existing) = self.devices.iter_mut().find(|d| d.id == id) {
            existing.device_name = device_name.to_string();
            let updated = existing.clone();
            self.save()?;
            return Ok(updated);
        }
        let device = PairedDevice {
            id: id.to_string(),
            device_name: device_name.to_string(),
            device_token: device_token.to_string(),
            pairing_salt: pairing_salt.to_string(),
            paired_at: chrono_now_iso8601(),
        };
        self.devices.push(device.clone());
        self.save()?;
        Ok(device)
    }

    pub fn remove(&mut self, id: &str) -> std::io::Result<()> {
        self.devices.retain(|d| d.id != id);
        self.save()
    }

    pub fn devices(&self) -> &[PairedDevice] {
        &self.devices
    }
}

fn old_swift_app_paired_devices_path() -> Option<PathBuf> {
    if cfg!(target_os = "macos") {
        dirs::home_dir().map(|home| {
            home.join("Library/Application Support/LinkToMac/paired-devices.json")
        })
    } else {
        None
    }
}

/// No `chrono`/`time` dependency pulled in just for one timestamp — a simple
/// seconds-since-epoch-derived RFC3339-ish string is enough; nothing on the wire parses this
/// field (it's Mac-local bookkeeping only, never sent to Android).
fn chrono_now_iso8601() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default();
    format!("{}", now.as_secs())
}
